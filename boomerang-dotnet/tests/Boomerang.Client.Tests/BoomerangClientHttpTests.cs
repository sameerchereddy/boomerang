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
        Assert.Equal(401, ex.StatusCode);
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

    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _fn;

        public StubHandler(Func<HttpRequestMessage, HttpResponseMessage> fn) => _fn = fn;

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) =>
            Task.FromResult(_fn(request));
    }
}
