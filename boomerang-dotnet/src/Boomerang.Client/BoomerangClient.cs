using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Boomerang.Client.Exceptions;
using Boomerang.Client.Models;

namespace Boomerang.Client;

/// <summary>
/// Thin HTTP client for Boomerang job APIs. Does not issue JWTs — supply a token or <see cref="BoomerangClientOptions.GetTokenAsync"/>.
/// </summary>
public sealed class BoomerangClient : IDisposable
{
    private readonly BoomerangClientOptions _options;
    private readonly HttpClient _http;
    private readonly bool _disposeHttp;
    private readonly Uri _normalizedBase;

    public BoomerangClient(BoomerangClientOptions options)
    {
        _options = options ?? throw new ArgumentNullException(nameof(options));
        if (options.BaseUrl == null) throw new ArgumentException("BaseUrl is required.", nameof(options));
        _normalizedBase = NormalizeBase(options.BaseUrl);

        if (options.HttpClient != null)
        {
            if (options.HttpClient.BaseAddress != null)
            {
                var normalizedHttpBase = NormalizeBase(options.HttpClient.BaseAddress);
                if (normalizedHttpBase != _normalizedBase)
                {
                    throw new ArgumentException(
                        "When BoomerangClientOptions.HttpClient has BaseAddress set, it takes precedence for relative request URIs. " +
                        "Ensure HttpClient.BaseAddress matches BaseUrl, or leave HttpClient.BaseAddress null.",
                        nameof(options));
                }
            }

            _http = options.HttpClient;
            _disposeHttp = false;
        }
        else
        {
            _http = new HttpClient { BaseAddress = _normalizedBase };
            _disposeHttp = true;
        }
    }

    private static Uri NormalizeBase(Uri u)
    {
        var s = u.ToString();
        if (!s.EndsWith('/'))
            return new Uri(s + "/", UriKind.Absolute);
        return u;
    }

    private string ApiPrefix
    {
        get
        {
            var p = _options.ApiPath.Trim();
            if (string.IsNullOrEmpty(p)) return "jobs";
            return p.TrimStart('/').TrimEnd('/');
        }
    }

    private Uri BuildRelativeUri(string relativePath)
    {
        // BaseAddress ends with /; relative should not start with /
        var rel = string.IsNullOrEmpty(relativePath) ? ApiPrefix : $"{ApiPrefix}/{relativePath}";
        return new Uri(rel, UriKind.Relative);
    }

    private async Task<string> GetBearerTokenAsync(CancellationToken ct)
    {
        if (_options.GetTokenAsync != null)
            return await _options.GetTokenAsync(ct).ConfigureAwait(false);
        if (!string.IsNullOrEmpty(_options.Token))
            return _options.Token;
        throw new InvalidOperationException("Set BoomerangClientOptions.Token or GetTokenAsync.");
    }

    private async Task<HttpRequestMessage> CreateRequestAsync(HttpMethod method, Uri relativeUri, HttpContent? content, CancellationToken ct)
    {
        var token = await GetBearerTokenAsync(ct).ConfigureAwait(false);
        var req = new HttpRequestMessage(method, relativeUri) { Content = content };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        return req;
    }

    private static async Task EnsureSuccessOrThrowAsync(HttpResponseMessage response, CancellationToken ct)
    {
        if (response.IsSuccessStatusCode)
            return;

        var body = response.Content == null ? null : await response.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        var code = (int)response.StatusCode;

        throw code switch
        {
            (int)HttpStatusCode.Unauthorized => new BoomerangUnauthorizedException(body),
            (int)HttpStatusCode.Forbidden => new BoomerangForbiddenException(body),
            (int)HttpStatusCode.NotFound => new BoomerangNotFoundException(body),
            (int)HttpStatusCode.Conflict => ParseConflict(body),
            (int)HttpStatusCode.ServiceUnavailable => new BoomerangServiceUnavailableException(body),
            _ => new BoomerangApiException(code, body),
        };
    }

    private static BoomerangConflictException ParseConflict(string? body)
    {
        long? retry = null;
        if (!string.IsNullOrEmpty(body))
        {
            try
            {
                using var doc = JsonDocument.Parse(body);
                if (doc.RootElement.TryGetProperty("retryAfterSeconds", out var r) && r.TryGetInt64(out var v))
                    retry = v;
            }
            catch (JsonException) { /* ignore */ }
        }

        return new BoomerangConflictException(body, retry);
    }

    /// <summary>Enqueue a job. Returns 202 response with <see cref="BoomerangTriggerResponse.JobId"/>.</summary>
    public async Task<BoomerangTriggerResponse> TriggerAsync(BoomerangTriggerRequest request, CancellationToken cancellationToken = default)
    {
        var json = JsonSerializer.Serialize(request, BoomerangJson.SerializerOptions);
        using var content = new StringContent(json, Encoding.UTF8, "application/json");
        using var req = await CreateRequestAsync(HttpMethod.Post, BuildRelativeUri(""), content, cancellationToken).ConfigureAwait(false);
        using var response = await SendAsync(req, cancellationToken).ConfigureAwait(false);

        if (response.StatusCode != HttpStatusCode.Accepted)
            await EnsureSuccessOrThrowAsync(response, cancellationToken).ConfigureAwait(false);

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        var result = await JsonSerializer.DeserializeAsync<BoomerangTriggerResponse>(stream, BoomerangJson.SerializerOptions, cancellationToken).ConfigureAwait(false);
        if (result?.JobId == null)
            throw new BoomerangApiException((int)response.StatusCode, null, "Expected 202 body with jobId.");
        return result;
    }

    /// <summary>Poll job status for the authenticated caller.</summary>
    public async Task<BoomerangJobStatusResponse> PollAsync(string jobId, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(jobId)) throw new ArgumentException("jobId is required.", nameof(jobId));
        using var req = await CreateRequestAsync(HttpMethod.Get, BuildRelativeUri(jobId), null, cancellationToken).ConfigureAwait(false);
        using var response = await SendAsync(req, cancellationToken).ConfigureAwait(false);
        await EnsureSuccessOrThrowAsync(response, cancellationToken).ConfigureAwait(false);

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        var result = await JsonSerializer.DeserializeAsync<BoomerangJobStatusResponse>(stream, BoomerangJson.SerializerOptions, cancellationToken).ConfigureAwait(false);
        if (result == null)
            throw new BoomerangApiException((int)response.StatusCode, null, "Empty poll response.");
        return result;
    }

    /// <summary>Lists dead-letter webhook entries (same JSON as Java controller).</summary>
    public async Task<JsonElement> ListFailedWebhooksAsync(CancellationToken cancellationToken = default)
    {
        using var req = await CreateRequestAsync(HttpMethod.Get, BuildRelativeUri("failed-webhooks"), null, cancellationToken).ConfigureAwait(false);
        using var response = await SendAsync(req, cancellationToken).ConfigureAwait(false);
        await EnsureSuccessOrThrowAsync(response, cancellationToken).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);
        return doc.RootElement.Clone();
    }

    /// <summary>Replays a dead-letter webhook for the given job id.</summary>
    public async Task<JsonElement> ReplayFailedWebhookAsync(string jobId, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(jobId)) throw new ArgumentException("jobId is required.", nameof(jobId));
        var path = $"failed-webhooks/{Uri.EscapeDataString(jobId)}/replay";
        using var req = await CreateRequestAsync(HttpMethod.Post, BuildRelativeUri(path), null, cancellationToken).ConfigureAwait(false);
        using var response = await SendAsync(req, cancellationToken).ConfigureAwait(false);
        await EnsureSuccessOrThrowAsync(response, cancellationToken).ConfigureAwait(false);
        var text = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        using var doc = JsonDocument.Parse(text);
        return doc.RootElement.Clone();
    }

    /// <summary>Deletes a dead-letter webhook entry.</summary>
    public async Task DeleteFailedWebhookAsync(string jobId, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(jobId)) throw new ArgumentException("jobId is required.", nameof(jobId));
        var path = $"failed-webhooks/{Uri.EscapeDataString(jobId)}";
        using var req = await CreateRequestAsync(HttpMethod.Delete, BuildRelativeUri(path), null, cancellationToken).ConfigureAwait(false);
        using var response = await SendAsync(req, cancellationToken).ConfigureAwait(false);
        await EnsureSuccessOrThrowAsync(response, cancellationToken).ConfigureAwait(false);
    }

    private async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        if (_http.BaseAddress == null && request.RequestUri != null && !request.RequestUri.IsAbsoluteUri)
            request.RequestUri = new Uri(_normalizedBase, request.RequestUri);

        return await _http.SendAsync(request, cancellationToken).ConfigureAwait(false);
    }

    /// <inheritdoc />
    public void Dispose()
    {
        if (_disposeHttp)
            _http.Dispose();
    }
}
