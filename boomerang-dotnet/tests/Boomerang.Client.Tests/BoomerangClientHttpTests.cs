using System.Net;
using Boomerang.Client.Exceptions;
using Boomerang.Client.Models;
using Xunit;

namespace Boomerang.Client.Tests;

public class BoomerangClientHttpTests
{
    private static BoomerangClient CreateClient(HttpMessageHandler handler) =>
        new(new BoomerangClientOptions
        {
            BaseUrl = new Uri("http://localhost:8080/"),
            ApiPath = "/jobs",
            Token = "test-token",
            HttpClient = new HttpClient(handler) { BaseAddress = new Uri("http://localhost:8080/") },
        });

    [Fact]
    public void Constructor_throws_when_external_httpclient_baseaddress_mismatches_options_baseurl()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK));
        var options = new BoomerangClientOptions
        {
            BaseUrl = new Uri("http://localhost:8080/"),
            ApiPath = "/jobs",
            Token = "test-token",
            HttpClient = new HttpClient(handler) { BaseAddress = new Uri("http://localhost:9999/") },
        };

        var ex = Assert.Throws<ArgumentException>(() => new BoomerangClient(options));
        Assert.Contains("BaseAddress", ex.Message);
        Assert.Contains("BaseUrl", ex.Message);
    }

    [Fact]
    public async Task Constructor_accepts_matching_external_httpclient_baseaddress()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Accepted)
        {
            Content = new StringContent("""{"jobId":"abc-123"}""", System.Text.Encoding.UTF8, "application/json"),
        });

        using var client = new BoomerangClient(new BoomerangClientOptions
        {
            BaseUrl = new Uri("http://localhost:8080"),
            ApiPath = "/jobs",
            Token = "test-token",
            HttpClient = new HttpClient(handler) { BaseAddress = new Uri("http://localhost:8080/") },
        });

        var response = await client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" });
        Assert.Equal("abc-123", response.JobId);
    }

    [Fact]
    public async Task TriggerAsync_maps_202_to_response()
    {
        var handler = new StubHandler(_ =>
        {
            var res = new HttpResponseMessage(HttpStatusCode.Accepted)
            {
                Content = new StringContent("""{"jobId":"abc-123"}""", System.Text.Encoding.UTF8, "application/json"),
            };
            return res;
        });
        using var client = CreateClient(handler);
        var r = await client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" });
        Assert.Equal("abc-123", r.JobId);
    }

    [Fact]
    public async Task TriggerAsync_throws_unauthorized_on_401()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Unauthorized)
        {
            Content = new StringContent("""{"error":"Authentication required"}"""),
        });
        using var client = CreateClient(handler);
        var ex = await Assert.ThrowsAsync<BoomerangUnauthorizedException>(() =>
            client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" }));
        Assert.Equal(HttpStatusCode.Unauthorized, ex.StatusCode);
    }

    [Fact]
    public async Task TriggerAsync_throws_conflict_on_409_with_retry()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Conflict)
        {
            Content = new StringContent("""{"error":"dup","retryAfterSeconds":42}"""),
        });
        using var client = CreateClient(handler);
        var ex = await Assert.ThrowsAsync<BoomerangConflictException>(() =>
            client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" }));
        Assert.Equal(42, ex.RetryAfterSeconds);
    }

    [Fact]
    public async Task PollAsync_throws_not_found_on_404()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.NotFound));
        using var client = CreateClient(handler);
        await Assert.ThrowsAsync<BoomerangNotFoundException>(() => client.PollAsync("jid"));
    }

    [Fact]
    public async Task TriggerAsync_throws_forbidden_on_403()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.Forbidden)
        {
            Content = new StringContent("""{"error":"callbackUrl not in allowlist"}"""),
        });
        using var client = CreateClient(handler);
        var ex = await Assert.ThrowsAsync<BoomerangForbiddenException>(() =>
            client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" }));
        Assert.Equal(HttpStatusCode.Forbidden, ex.StatusCode);
    }

    [Fact]
    public async Task TriggerAsync_throws_service_unavailable_on_503()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)
        {
            Content = new StringContent("""{"error":"worker pool saturated"}"""),
        });
        using var client = CreateClient(handler);
        var ex = await Assert.ThrowsAsync<BoomerangServiceUnavailableException>(() =>
            client.TriggerAsync(new BoomerangTriggerRequest { CallbackUrl = "https://ex.com/h" }));
        Assert.Equal(HttpStatusCode.ServiceUnavailable, ex.StatusCode);
    }

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _fn;

        public StubHandler(Func<HttpRequestMessage, HttpResponseMessage> fn) => _fn = fn;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(_fn(request));
    }
}
