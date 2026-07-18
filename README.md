# 🩺 llm-health-monitor

**Catch LLM outages the providers' status pages don't admit.**

A tiny, zero-database health monitor for LLM APIs, built with **Kotlin + Ktor**.
It probes every model you configure — across **OpenAI, Anthropic, Google Gemini, and xAI** — with the
cheapest possible request (1 output token), classifies each as `ok / slow / error / unavailable`,
and stores results as **plain JSON files**. Clone, add your keys, run.

## Why not just watch status.openai.com?

Because official status pages are famously the last to know. Real incidents routinely produce
elevated error rates and multi-x latency long before (or without) the provider acknowledging
anything. This tool measures **reality** — actual API probes from your own network — and fetches
each provider's official status page **at the same moment**, so the gap between the two becomes
a first-class, queryable signal:

```json
// GET /api/health/insights
{
  "model_name": "gpt-5-mini", "provider": "openai",
  "observed_status": "error", "official_indicator": "none",
  "latency_ratio": 3.4, "baseline_latency_ms": 850,
  "hidden_degradation": true,
  "note": "Probe failed (error) while the official status page reports all operational."
}
```

`hidden_degradation` fires when a probe fails — or runs ≥2× that model's **own median baseline** —
while the official page still says "All Systems Operational". The dashboard shows these as
red banner alerts, and Prometheus exposes them as `llm_health_hidden_degradation`.

Born from a production multi-LLM service (ZeliAI) where knowing
*"which provider is down right now?"* before users notice turned out to matter a lot.

## Features

- ⏱ **Startup + scheduled checks** — one full probe when the server boots, then every N minutes
- 🪶 **Minimal-cost probes** — 1-token requests; image models checked via metadata endpoints only
- 📁 **JSON file storage** — `data/latest.json` (current status) + `data/history.jsonl` (append-only runs, auto-trimmed)
- 🕵️ **Hidden-degradation detection** — observed probes vs. official status pages, per-model latency baselines
- 🌐 **Read-only HTTP API** + a single-file **dark dashboard** at `/` with per-model uptime % and red hidden-degradation alerts
- 🔔 **Webhook alerts** — one URL works for Slack, Discord, or any JSON consumer; fires on down/recovered transitions only
- 🏷 **Live README badge** — Shields.io endpoint at `/api/health/badge`
- 📈 **Prometheus metrics** at `/metrics` — drop into an existing Grafana setup
- 🐳 **Docker-ready** — multi-stage `Dockerfile` included
- 🧩 **Pluggable providers** — implement one small interface to add a provider
- 🚫 **Graceful key handling** — providers without an API key are marked `skipped`, never crash

## How it compares

| | llm-health-monitor | LiteLLM proxy health checks | Langfuse / OpenLLMetry / Opik | Uptime Kuma / Gatus |
|---|---|---|---|---|
| Purpose | "Are my LLM providers up?" | Part of a full LLM gateway | LLM observability (traces, evals, cost) | Generic uptime monitoring |
| Setup | Clone + keys + run (no DB) | Run the whole proxy | DB + SDK integration | Generic — you write per-API probes yourself |
| LLM-specific probes | ✅ built-in (incl. o-series/image quirks) | ✅ | ❌ (instruments your app's calls) | ❌ manual |
| Footprint | Single small JVM app, JSON files | Medium | Heavy | Medium |

If you need request tracing, evals, or cost analytics, use an observability platform — this tool
deliberately does one small thing well.

## Quick start

```bash
git clone https://github.com/YOUR_USERNAME/llm-health-monitor.git
cd llm-health-monitor

# 1. Configure models
cp config.example.json config.json     # edit the model list if you like

# 2. Provide API keys (only the ones you have — others are skipped)
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
export GEMINI_API_KEY=...
export XAI_API_KEY=xai-...

# 3. Run
./gradlew run
```

Open <http://localhost:8080> for the dashboard.

### Or with Docker

```bash
docker build -t llm-health-monitor .
docker run -p 8080:8080 \
  -v ./config.json:/app/config.json -v ./data:/app/data \
  -e OPENAI_API_KEY=sk-... -e ANTHROPIC_API_KEY=sk-ant-... \
  llm-health-monitor
```

## API

| Endpoint | Description |
|---|---|
| `GET /api/health/models` | Latest status of every configured model |
| `GET /api/health/history?limit=24` | Recent check runs, newest first |
| `GET /api/health/uptime?runs=100` | Per-model uptime % + avg latency over recent runs |
| `GET /api/health/insights` | Observed reality vs. official status pages — hidden degradation flags |
| `GET /api/health/badge` | Shields.io endpoint-badge JSON |
| `GET /metrics` | Prometheus text exposition format |
| `POST /api/health/check` | Trigger an immediate re-check |

### Live badge in your README

```markdown
![LLM status](https://img.shields.io/endpoint?url=https://your-host/api/health/badge)
```

### Webhook alerts

Set `"webhookUrl"` in `config.json` to a Slack incoming webhook, Discord webhook, or any
HTTP endpoint. A notification fires **only on transitions** (a model going down, or recovering) —
no hourly noise. The payload contains `text` (Slack), `content` (Discord), and a structured
`events` array for programmatic consumers.

Example response (`GET /api/health/models`):

```json
{
  "status": "success",
  "last_checked_at": "2026-07-18 09:00:12",
  "summary": { "total_models": 8, "ok_count": 7, "slow_count": 0,
               "error_count": 0, "unavailable_count": 1, "skipped_count": 0 },
  "models": [
    { "model_name": "gpt-5-mini", "provider": "openai", "model_type": "text",
      "status": "ok", "latency_ms": 842, "checked_at": "2026-07-18 09:00:12" }
  ]
}
```

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
| `uptimeWindowRuns` | `100` | Runs counted for the uptime percentage |
| `degradedLatencyRatio` | `2.0` | Latency ≥ this × the model's median baseline → degradation |
| `officialStatusUrls` | OpenAI + Anthropic | Statuspage JSON endpoints compared against observed probes |
| `models` | — | List of `{name, provider, type}` |

Supported `provider` values: `openai`, `anthropic`, `google`, `xai` (alias: `grok`).

## Adding a provider

Implement `ProviderChecker` and register it:

```kotlin
object MyProviderChecker : ProviderChecker {
    override val provider = "myprovider"
    override suspend fun check(client: HttpClient, model: ModelInfo, apiKey: String) {
        // smallest possible request; throw on failure
    }
}
```

Add it to `ProviderRegistry` and use `"provider": "myprovider"` in `config.json`.
API key is read from the `MYPROVIDER_API_KEY`-style mapping in `ApiKeys` (add a branch there too).

## Status semantics

| Status | Meaning |
|---|---|
| `ok` | Responded within `slowThresholdMs` |
| `slow` | Responded, but slower than the threshold |
| `error` | Provider returned a non-OK response (bad model id, quota, auth…) |
| `unavailable` | Request timed out |
| `skipped` | No API key configured for this provider |

## Notes

- API keys are read from **environment variables only** — they are never written to config or data files.
- The check endpoints cost a few tokens per model per run. With 1-token outputs and hourly checks,
  monthly cost is typically well under $1 per provider.
- `POST /api/health/check` is unauthenticated by design (self-hosted tool). Put it behind a reverse
  proxy or add auth if you expose it publicly.

## License

[MIT](LICENSE)
