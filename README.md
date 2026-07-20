# 🩺 llm-health-monitor

> **Catch LLM outages the providers' status pages don't admit.**

[![build](https://github.com/hajunho/llm-health-monitor/actions/workflows/build.yml/badge.svg)](https://github.com/hajunho/llm-health-monitor/actions)
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![ktor](https://img.shields.io/badge/Ktor-3.3-087CFA)
![no database](https://img.shields.io/badge/database-none-brightgreen)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](#contributing)

A tiny, zero-database health monitor for LLM APIs. It probes every model you configure —
across **OpenAI, Anthropic, Google Gemini, and xAI** — with the cheapest possible request
(1 output token), fetches each provider's **official status page at the same moment**, and turns
the gap between *what they claim* and *what you observe* into a first-class, queryable signal.

Everything is stored as **plain JSON files**. Clone, add your keys, run. That's the whole setup.

---

## Why does this exist?

**Official status pages are famously the last to know.** Real incidents routinely produce elevated
error rates and multi-x latency long before — or entirely without — the provider acknowledging
anything. Every existing tool falls on the wrong side of this problem:

- **Observability platforms** (Langfuse, OpenLLMetry, Opik) trace *your own* traffic — they can't
  tell you a provider is down before your users hit it, and they need a database + SDK integration.
- **Generic uptime tools** (Uptime Kuma, Gatus) can ping a URL, but you'd hand-write every
  provider's auth headers, request quirks, and body formats yourself — and they still can't say
  *"this is slow **for this model**"* or *"the vendor hasn't admitted this yet."*
- **LLM gateways** (LiteLLM) include health endpoints, but you must adopt the whole proxy.

This tool does one thing the others don't, in ~1,000 lines you can read in one sitting.

## The mechanism

```mermaid
flowchart LR
    S[Scheduler<br/>boot + every N min] --> P1[Probe every model<br/>1-token requests, parallel]
    S --> P2[Fetch official status pages<br/>same moment, parallel]
    P1 --> C{Compare}
    P2 --> C
    H[(history.jsonl)] -->|per-model median<br/>latency baseline| C
    C -->|observed ≠ claimed| HD[🚨 hidden_degradation]
    C --> J[(latest.json)]
    HD --> W[Webhook<br/>Slack / Discord]
    HD --> M[Prometheus<br/>/metrics]
    HD --> D[Dashboard<br/>red alert banner]
```

Three ideas working together:

1. **Reality, not claims** — actual API probes from your own network, classified
   `ok / slow / error / unavailable` per model.
2. **Self-referential baselines** — each model is compared against **its own median latency**
   from history, not a global threshold. A model that normally answers in 800 ms suddenly taking
   3 s is degraded; a reasoning model that always takes 3 s is fine.
3. **The claim-vs-reality diff** — official Statuspage JSON is fetched *in the same instant* as
   the probes. When probes fail (for `confirmationRuns` consecutive runs, to ignore blips) or run
   ≥ `degradedLatencyRatio`× baseline **while the vendor still says "All Systems Operational"**,
   the model is flagged:

```json
// GET /api/health/insights
{
  "model_name": "gpt-5-mini",  "provider": "openai",
  "observed_status": "error",  "official_indicator": "none",
  "baseline_latency_ms": 850,  "latency_ratio": 3.4,
  "consecutive_failures": 2,   "hidden_degradation": true,
  "note": "Probe failed 2 time(s) in a row (error) while the official status page reports all operational."
}
```

That flag is what pages you before your users notice — and before the vendor tweets.

## Features

- 🕵️ **Hidden-degradation detection** — observed probes vs. official status pages, per-model latency baselines, blip-resistant confirmation
- ⏱ **Startup + scheduled checks** — one full probe when the server boots, then every N minutes
- 🪶 **Minimal-cost probes** — 1-token requests; image models via free metadata endpoints; typically well under $1/month per provider
- 📁 **JSON file storage** — `data/latest.json` + append-only `data/history.jsonl` (auto-trimmed). No database, ever
- 🌐 **Read-only HTTP API** + single-file **dark dashboard** with uptime % and red alert banners
- 🔔 **Webhook alerts** — one URL serves Slack (`text`), Discord (`content`), and machines (`events[]`); fires on transitions only, zero hourly noise
- 🏷 **Live README badge** (Shields.io endpoint) &nbsp;·&nbsp; 📈 **Prometheus `/metrics`** &nbsp;·&nbsp; 🐳 **Dockerfile**
- 🔑 **In-dashboard key setup** — paste keys in the browser (localhost-only endpoints, masked previews, gitignored storage); env vars still supported for servers
- 🧩 **Pluggable providers** — one small interface; provider quirks (o-series `max_completion_tokens`, Gemini URL scheme, Anthropic version header) already handled
- 🚫 **Graceful key handling** — providers without an API key are `skipped`, never crash

## Quick start

```bash
git clone https://github.com/hajunho/llm-health-monitor.git
cd llm-health-monitor
cp config.example.json config.json      # pick your models (defaults are fine)
./gradlew run
```

Open <http://localhost:8080>, expand **🔑 API keys**, paste the keys you have, done —
a fresh check runs automatically with each key you save. No environment variables,
no restart, no editor.

## Setting API keys

Two ways, use whichever fits:

| Method | Best for | How |
|---|---|---|
| **Dashboard UI** (recommended locally) | Your own machine | Open the dashboard → 🔑 API keys → paste → Save |
| **Environment variables** | Servers, Docker, CI | `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `XAI_API_KEY` |

How the dashboard method works, and why it's safe *for a local machine*:

- Keys are saved to **`data/keys.json`** on your disk — plain JSON, **gitignored**, never leaves
  your machine. Delete the file (or click *Remove*) to forget a key.
- The key-management endpoints (`/api/keys`) accept **loopback connections only** — even if you
  expose port 8080 to your network, remote clients get `403` and can neither read nor set keys.
- The API only ever returns **masked previews** (`sk-pr…Xk4Q`); full keys are never sent back out.
- Precedence: a dashboard-set key overrides the env var for the same provider.
- Providers without any key are simply marked `skipped` — nothing crashes.

For **servers or containers**, prefer environment variables (see `.env.example`) and treat the
dashboard method as what it is: a convenience for localhost.

<details>
<summary><b>Docker</b></summary>

```bash
docker build -t llm-health-monitor .
docker run -p 8080:8080 \
  -v ./config.json:/app/config.json -v ./data:/app/data \
  -e OPENAI_API_KEY=sk-... -e ANTHROPIC_API_KEY=sk-ant-... \
  llm-health-monitor
```
</details>

## API

| Endpoint | Description |
|---|---|
| `GET /api/health/models` | Latest status of every configured model + official provider status |
| `GET /api/health/insights` | **Claim-vs-reality diff** — hidden degradation flags, baselines, streaks |
| `GET /api/health/uptime?runs=100` | Per-model uptime % + average latency over recent runs |
| `GET /api/health/history?limit=24` | Recent check runs, newest first |
| `GET /api/health/badge` | Shields.io endpoint-badge JSON |
| `GET /metrics` | Prometheus text exposition (incl. `llm_health_hidden_degradation`) |
| `POST /api/health/check` | Trigger an immediate re-check |
| `GET/POST /api/keys`, `DELETE /api/keys/{provider}` | Key management — **loopback-only**, masked responses |

**Live badge for your README:**

```markdown
![LLM status](https://img.shields.io/endpoint?url=https://your-host/api/health/badge)
```

**Webhook alerts:** set `"webhookUrl"` to a Slack incoming webhook, Discord webhook, or any HTTP
endpoint. Notifications fire **only on transitions** (down / recovered).

## Configuration (`config.json`)

| Key | Default | Description |
|---|---|---|
| `port` | `8080` | HTTP port |
| `checkIntervalMinutes` | `60` | Minutes between checks |
| `slowThresholdMs` | `15000` | Latency above this → `slow` |
| `requestTimeoutMs` | `30000` | Per-probe timeout → `unavailable` |
| `dataDir` | `data` | Where JSON files are written |
| `historyMaxRuns` | `1000` | Max runs kept in `history.jsonl` |
| `webhookUrl` | `null` | Slack/Discord/generic webhook for down/recovery alerts |
| `uptimeWindowRuns` | `100` | Runs counted for uptime % and latency baselines |
| `degradedLatencyRatio` | `2.0` | Latency ≥ this × the model's median baseline → degradation |
| `confirmationRuns` | `1` | Consecutive failures required before flagging (raise to 2–3 to ignore blips) |
| `officialStatusUrls` | OpenAI + Anthropic | Statuspage JSON endpoints compared against observed probes |
| `models` | — | List of `{name, provider, type}` |

Supported providers: `openai`, `anthropic`, `google`, `xai` (alias `grok`).

## Status semantics

| Status | Meaning |
|---|---|
| `ok` | Responded within `slowThresholdMs` |
| `slow` | Responded, but slower than the threshold |
| `error` | Provider returned a non-OK response (bad model id, quota, auth…) |
| `unavailable` | Request timed out |
| `skipped` | No API key configured for this provider |

## Adding a provider

```kotlin
object MyProviderChecker : ProviderChecker {
    override val provider = "myprovider"
    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        // smallest possible request; throw on failure
    }
}
```

Register it in `ProviderRegistry`, add a key branch in `ApiKeys`, and use
`"provider": "myprovider"` in `config.json`. If the provider runs an Atlassian Statuspage,
add its `/api/v2/status.json` URL to `officialStatusUrls` to get hidden-degradation detection too.

## Roadmap

- [ ] Response-content sanity probes (detect degraded *quality*, not just availability)
- [ ] Official status endpoints for Google / xAI (non-Statuspage formats)
- [ ] Regional probing (compare latency across regions)
- [ ] Historical hidden-degradation report ("incidents the vendor never acknowledged")

## Contributing

Issues and PRs welcome — especially new provider checkers and official-status adapters.
The whole codebase is small by design; please keep it that way.

## Notes

- API keys never appear in `config.json` or in any API response — dashboard keys live in the
  gitignored `data/keys.json` (localhost management only), env vars remain server-grade.
- The Gradle wrapper JAR is the official Gradle 9.1 artifact, and the distribution download is
  SHA-256-pinned in `gradle-wrapper.properties`. Gradle 9.1 runs on any JDK 17–25; the build
  toolchain (JDK 21) is auto-provisioned via the Foojay resolver.
- `POST /api/health/check` is unauthenticated by design (self-hosted tool). Put it behind a
  reverse proxy or add auth if you expose it publicly.

Born from a production multi-LLM service (ZeliAI) where knowing *"which provider is down right
now?"* before users notice turned out to matter a lot.

## References & related projects

Compared against / inspired by — all worth knowing:

- [LiteLLM](https://github.com/BerriAI/litellm) — LLM gateway with built-in [health check endpoints](https://docs.litellm.ai/docs/proxy/health)
- [Langfuse](https://github.com/langfuse/langfuse) · [OpenLLMetry](https://github.com/traceloop/openllmetry) · [Opik](https://github.com/comet-ml/opik) — LLM observability platforms (tracing/evals of your own traffic)
- [Uptime Kuma](https://github.com/louislam/uptime-kuma) · [Gatus](https://github.com/TwiN/gatus) — the generic self-hosted uptime monitors whose badge/alert/uptime UX this tool borrows
- [Atlassian Statuspage API](https://developer.statuspage.io/) — the `status.json` format used for official-status fetching
- [Shields.io endpoint badges](https://shields.io/badges/endpoint-badge) — the live badge format served at `/api/health/badge`

## License

[MIT](LICENSE)
