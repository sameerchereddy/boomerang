# Boomerang .NET samples

## Boomerang.Sample.Web

ASP.NET Core 8 app that references **Boomerang.Client** and **Boomerang.Client.AspNetCore** as NuGet packages (`PackageReference`), the same way a consumer app would. It includes a small **browser dashboard** (`wwwroot`) plus JSON APIs under `/demo` and `/hooks`.

### Prerequisite: local package feed

The sample adds `RestoreAdditionalProjectSources` pointing at the repository root **`artifacts`** folder (see [Boomerang.Sample.Web.csproj](Boomerang.Sample.Web/Boomerang.Sample.Web.csproj)). Build the libraries into that folder first (from `boomerang-dotnet`):

```bash
dotnet pack src/Boomerang.Client/Boomerang.Client.csproj -c Release -o ../artifacts
dotnet pack src/Boomerang.Client.AspNetCore/Boomerang.Client.AspNetCore.csproj -c Release -o ../artifacts
```

Then restore and run the sample:

```bash
dotnet run --project samples/Boomerang.Sample.Web/Boomerang.Sample.Web.csproj
```

After the packages are published to nuget.org (or another feed), you can remove the extra restore source and use `dotnet add package` as usual.

### Browser dashboard

1. Run the sample (see above).
2. Open **`https://localhost:7123/`** (redirects to `index.html`) or **`https://localhost:7123/index.html`** — ports match [launchSettings.json](Boomerang.Sample.Web/Properties/launchSettings.json).
3. Use **Add job** to call `POST /demo/jobs` (the UI sends a fresh `idempotencyKey` each click). The server fills the default callback URL from config. The table polls job status until `DONE` / `FAILED` or a cap; use **Stop polling** / **Refresh** per row.

   **Note:** If you call `POST /demo/jobs` without an `idempotencyKey`, Boomerang uses your JWT `sub` as the key, so a second job within the idempotency cooldown returns **409 Conflict**. The bundled UI avoids that by generating a UUID per enqueue.
4. The right panel shows the default callback URL, a short note about secrets, and the **last webhook** payload (polled from `GET /demo/last-webhook` after Boomerang calls `POST /hooks/done`).

### Full demo checklist (Boomerang can reach your callback)

- **Boomerang** running and reachable at `Boomerang:BaseUrl` (e.g. `http://localhost:8080/`).
- **Auth**: `Boomerang:Jwt` or `Boomerang:JwtSecret` (plus `JwtSub`) so the sample can call the API.
- **Public callback URL**: Boomerang must POST to a URL it can reach. If the sample runs on `localhost`, use a tunnel (**ngrok**, Cloudflare Tunnel, etc.) and set **`Boomerang:SamplePublicBaseUrl`** to that **HTTPS** public origin (no trailing slash required). The default callback becomes `{SamplePublicBaseUrl}/hooks/done`.
- **Signed webhooks**: Set **`WEBHOOK_SECRET`** in the environment to the same value used as `callbackSecret` when enqueueing (the `[BoomerangWebhook]` attribute reads this variable). Optionally set **`Webhook:Secret`** so the default enqueue path uses the same secret without putting it in the UI request body.

If Boomerang options are missing, demo APIs return **503**; the dashboard still loads and shows the reason. The **Add job** button stays disabled until `defaultCallbackUrl` can be resolved (`SamplePublicBaseUrl` or explicit `callbackUrl` in API calls).

### Configuration

| Keys / environment | Purpose |
|--------------------|---------|
| `Boomerang:BaseUrl` / `BOOMERANG__BASEURL` | Boomerang server base URL |
| `Boomerang:ApiPath` | API prefix (default `/jobs`) |
| `Boomerang:Jwt` / `BOOMERANG__JWT` | Static Bearer JWT |
| `Boomerang:JwtSecret` / `BOOMERANG__JWTSECRET` | HS256 secret — sample mints short-lived JWTs (local dev) |
| `Boomerang:JwtSub` | `sub` claim when minting (default `boomerang-sample`) |
| `Boomerang:SamplePublicBaseUrl` | Public URL of this app; used to build default `CallbackUrl` for `POST /demo/jobs` and shown in the UI |
| `WEBHOOK_SECRET` | Required on the **receiver** for `[BoomerangWebhook]` on `POST /hooks/done` (must match enqueue `callbackSecret` when signing is enabled) |
| `Webhook:Secret` | Optional; default `callbackSecret` when triggering if not passed in the JSON body. If set, must be **at least 32 characters** (Boomerang validation); empty/whitespace values are ignored so unsigned callbacks work |

### Endpoints

- `GET /demo/status` — readiness plus **`defaultCallbackUrl`**, **`webhookSecretConfigured`** (whether `Webhook:Secret` or `WEBHOOK_SECRET` is set), and existing fields (`baseUrl`, `apiPath`, JWT hints).
- `GET /demo/last-webhook` — last in-memory webhook from `POST /hooks/done` (`{ received: false }` or payload fields).
- `POST /demo/jobs` — trigger a job (optional JSON: `callbackUrl`, `callbackSecret`, `idempotencyKey`).
- `GET /demo/jobs/{jobId}` — poll status.
- `GET /demo/failed-webhooks`, `POST /demo/failed-webhooks/{jobId}/replay`, `DELETE /demo/failed-webhooks/{jobId}` — dead-letter helpers.

Webhook receiver:

- `POST /hooks/done` — verified with `X-Signature-SHA256` when `callbackSecret` was set at enqueue time and `WEBHOOK_SECRET` matches.

Static files: `GET /index.html`, `site.css`, `app.js` under `wwwroot`.
