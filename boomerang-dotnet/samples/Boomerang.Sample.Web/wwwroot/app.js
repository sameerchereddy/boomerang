(function () {
  "use strict";

  const POLL_MS = 1500;
  const MAX_POLLS = 60;

  /** @type {{ jobId: string, status: string, lastUpdated: string, message: string, timer: ReturnType<typeof setInterval> | null, pollsLeft: number }[]} */
  const rows = [];

  const el = (id) => document.getElementById(id);

  async function readError(res) {
    try {
      const j = await res.json();
      let msg = "";
      if (j && typeof j.error === "string") msg = j.error;
      else msg = JSON.stringify(j);
      if (j && typeof j.boomerangResponseBody === "string" && j.boomerangResponseBody.length)
        msg += " — " + j.boomerangResponseBody;
      if (j && j.retryAfterSeconds != null)
        msg += ` (retry after ~${j.retryAfterSeconds}s)`;
      return msg;
    } catch {
      return res.statusText || String(res.status);
    }
  }

  function setBanner(text, kind) {
    const b = el("globalBanner");
    if (!text) {
      b.classList.add("hidden");
      b.textContent = "";
      return;
    }
    b.textContent = text;
    b.classList.remove("hidden", "error", "info");
    b.classList.add(kind === "error" ? "error" : "info");
  }

  function statusClass(status) {
    const s = (status || "").toUpperCase();
    if (s === "DONE") return "done";
    if (s === "FAILED") return "failed";
    return "pending";
  }

  function renderTable() {
    const tbody = el("jobsBody");
    tbody.innerHTML = "";
    for (const r of rows) {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td class="mono">${escapeHtml(r.jobId)}</td>
        <td><span class="status-pill ${statusClass(r.status)}">${escapeHtml(r.status || "—")}</span></td>
        <td class="mono">${escapeHtml(r.lastUpdated)}</td>
        <td>${escapeHtml(r.message)}</td>
        <td class="row-actions">
          <button type="button" class="small btn-refresh" data-job="${escapeAttr(r.jobId)}">Refresh</button>
          <button type="button" class="small btn-stop" data-job="${escapeAttr(r.jobId)}">Stop polling</button>
        </td>`;
      tbody.appendChild(tr);
    }
    tbody.querySelectorAll(".btn-refresh").forEach((btn) => {
      btn.addEventListener("click", () => refreshRow(btn.getAttribute("data-job")));
    });
    tbody.querySelectorAll(".btn-stop").forEach((btn) => {
      btn.addEventListener("click", () => stopPolling(btn.getAttribute("data-job")));
    });
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function escapeAttr(s) {
    return escapeHtml(s).replace(/'/g, "&#39;");
  }

  function findRow(jobId) {
    return rows.find((x) => x.jobId === jobId);
  }

  function stopPolling(jobId) {
    const r = findRow(jobId);
    if (!r || !r.timer) return;
    clearInterval(r.timer);
    r.timer = null;
    r.message = r.message || "Polling stopped.";
    renderTable();
  }

  async function refreshRow(jobId) {
    const r = findRow(jobId);
    if (!r) return;
    try {
      const res = await fetch(`/demo/jobs/${encodeURIComponent(jobId)}`);
      if (!res.ok) {
        r.message = await readError(res);
        r.lastUpdated = new Date().toISOString();
        renderTable();
        return;
      }
      const data = await res.json();
      r.status = data.status || "";
      r.lastUpdated = new Date().toISOString();
      r.message = "";
      renderTable();
    } catch (e) {
      r.message = String(e);
      r.lastUpdated = new Date().toISOString();
      renderTable();
    }
  }

  function isTerminal(status) {
    const s = (status || "").toUpperCase();
    return s === "DONE" || s === "FAILED";
  }

  function startPolling(jobId) {
    const r = findRow(jobId);
    if (!r) return;
    if (r.timer) clearInterval(r.timer);
    r.pollsLeft = MAX_POLLS;
    r.timer = setInterval(async () => {
      const row = findRow(jobId);
      if (!row || !row.timer) return;
      row.pollsLeft -= 1;
      await refreshRow(jobId);
      const cur = findRow(jobId);
      if (!cur) return;
      if (isTerminal(cur.status) || cur.pollsLeft <= 0) {
        if (cur.timer) clearInterval(cur.timer);
        cur.timer = null;
        if (!isTerminal(cur.status) && cur.pollsLeft <= 0)
          cur.message = "Max poll attempts reached; use Refresh.";
        renderTable();
      }
    }, POLL_MS);
  }

  async function loadStatus() {
    try {
      const res = await fetch("/demo/status");
      const data = await res.json();
      const btn = el("btnAddJob");
      const hint = el("readyHint");

      el("callbackUrlDisplay").textContent = data.defaultCallbackUrl || "— (set Boomerang:SamplePublicBaseUrl)";
      el("secretHint").textContent = data.webhookSecretConfigured ? "Yes (Webhook:Secret or WEBHOOK_SECRET)" : "No — set for signed webhooks";

      if (data.ready) {
        btn.disabled = !data.defaultCallbackUrl;
        hint.textContent = data.defaultCallbackUrl
          ? "Ready to enqueue."
          : "Set SamplePublicBaseUrl (or pass callbackUrl) before adding a job.";
        setBanner("", "");
      } else {
        btn.disabled = true;
        hint.textContent = data.reason || "Not configured.";
        setBanner(data.reason || "Boomerang client is not configured.", "error");
      }
    } catch (e) {
      el("btnAddJob").disabled = true;
      setBanner("Failed to load /demo/status: " + e, "error");
    }
  }

  async function pollLastWebhook() {
    try {
      const res = await fetch("/demo/last-webhook");
      const data = await res.json();
      const pre = el("lastWebhookPre");
      if (!data.received) {
        pre.textContent = "None yet.";
        return;
      }
      pre.textContent = JSON.stringify(
        {
          receivedAtUtc: data.receivedAtUtc,
          jobId: data.jobId,
          status: data.status,
          completedAt: data.completedAt,
          resultJson: data.resultJson,
        },
        null,
        2
      );
    } catch (e) {
      el("lastWebhookPre").textContent = "Error: " + e;
    }
  }

  async function addJob() {
    setBanner("", "");
    el("btnAddJob").disabled = true;
    try {
      // Boomerang defaults idempotency to JWT sub when omitted — second job would be 409 until cooldown.
      const res = await fetch("/demo/jobs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          idempotencyKey: crypto.randomUUID(),
        }),
      });
      if (res.status === 202) {
        const data = await res.json();
        const jobId = data.jobId;
        rows.push({
          jobId,
          status: "—",
          lastUpdated: new Date().toISOString(),
          message: "Enqueued; polling…",
          timer: null,
          pollsLeft: MAX_POLLS,
        });
        renderTable();
        await refreshRow(jobId);
        startPolling(jobId);
        await loadStatus();
        return;
      }
      const err = await readError(res);
      setBanner(`${res.status}: ${err}`, "error");
    } catch (e) {
      setBanner(String(e), "error");
    } finally {
      await loadStatus();
    }
  }

  el("btnAddJob").addEventListener("click", addJob);

  loadStatus();
  setInterval(pollLastWebhook, 2000);
  pollLastWebhook();
})();
