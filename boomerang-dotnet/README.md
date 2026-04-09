# Boomerang .NET SDK

Thin HTTP clients for the [Boomerang](https://github.com/sameerchereddy/boomerang) async job API. These libraries **do not** issue JWTs or run Redis — they only call your deployed Boomerang service and help verify webhook signatures.

## Packages

| Package | Purpose |
|---------|---------|
| **Boomerang.Client** | `BoomerangClient`, models, `BoomerangSignature` (HMAC) |
| **Boomerang.Client.AspNetCore** | `[BoomerangWebhook]` + filter for ASP.NET Core |

Target framework: **.NET 8.0** (LTS). Compatible with newer runtimes.

## Sample app

See [samples/README.md](samples/README.md) for **Boomerang.Sample.Web**, a small ASP.NET Core app that consumes the packages from a local `artifacts` feed (or from nuget.org once published) and includes a **browser dashboard** plus APIs for trigger, poll, failed-webhooks, and signed webhooks.

## Install

```bash
dotnet add package Boomerang.Client
# optional — webhook receiver helpers
dotnet add package Boomerang.Client.AspNetCore
```

## Usage — trigger and poll

Obtain a Bearer JWT from your identity stack (HS256, `sub` claim; same secret as `boomerang.auth.jwt-secret` on the server). Then:

```csharp
using Boomerang.Client;
using Boomerang.Client.Models;

var client = new BoomerangClient(new BoomerangClientOptions
{
    BaseUrl = new Uri("http://localhost:8080/"),
    ApiPath = "/jobs",          // must match server boomerang.base-path
    Token = Environment.GetEnvironmentVariable("BOOMERANG_JWT")!,
    // Or: GetTokenAsync = async ct => await GetTokenFromYourIdP(ct),
});

var job = await client.TriggerAsync(new BoomerangTriggerRequest
{
    CallbackUrl = "https://yourapp.example.com/hooks/done",
    CallbackSecret = "optional-hmac-secret-min-32-chars!!",
});

var status = await client.PollAsync(job.JobId);
// status.Status: PENDING, IN_PROGRESS, DONE, FAILED
```

## Usage — ASP.NET Core webhook

```csharp
using Boomerang.Client.AspNetCore;
using Boomerang.Client.Models;

[ApiController]
[Route("hooks")]
public class HooksController : ControllerBase
{
    [HttpPost("done")]
    [BoomerangWebhook(SecretEnvironmentVariable = "WEBHOOK_SECRET")]
    public IActionResult OnDone([FromBody] BoomerangWebhookPayload payload)
    {
        // Signature already verified; payload matches Java BoomerangPayload JSON
        return Ok();
    }
}
```

Use the **same** secret you passed as `callbackSecret` when enqueueing.

`[BoomerangWebhook]` requires signed callbacks. Only use it when you pass `CallbackSecret`/`callbackSecret` at enqueue time; unsigned callbacks are rejected with `401`.

## Failed webhooks API

`ListFailedWebhooksAsync`, `ReplayFailedWebhookAsync`, and `DeleteFailedWebhookAsync` map to the Java controller routes under the same `ApiPath`.

## Integration tests (optional)

Set environment variables and run `dotnet test`:

- `BOOMERANG_TEST_BASE_URL` — e.g. `http://localhost:8080/`
- `BOOMERANG_JWT_SECRET` — HS256 secret matching the running server

If unset, the integration test exits immediately (pass).

Integration scaffolding is in place, but full issue #10 coverage requires running these tests in CI against a live Boomerang instance.

## Build from source

The solution includes **Boomerang.Sample.Web**, which restores **Boomerang.Client** packages from the repo-root **`artifacts`** folder. Pack the libraries first (or remove the sample from the solution) before `dotnet restore` / `dotnet build` on the full `.sln`.

```bash
cd boomerang-dotnet
dotnet pack src/Boomerang.Client/Boomerang.Client.csproj -c Release -o ../artifacts
dotnet pack src/Boomerang.Client.AspNetCore/Boomerang.Client.AspNetCore.csproj -c Release -o ../artifacts
dotnet build Boomerang.Client.sln -c Release
dotnet test Boomerang.Client.sln -c Release
```

## See also

- Repository [README.md](../README.md) — HTTP API and configuration
- [IMPLEMENTATION_PROMPT.md](IMPLEMENTATION_PROMPT.md) — contributor / agent spec (GitHub Issue #3)
