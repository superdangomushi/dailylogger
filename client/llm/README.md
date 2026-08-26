# AIHelper local LLM analyzer (Ollama)

This is a **separate, lightweight Python worker** that runs the text-analysis work
(課題・予定の抽出＋要約 と 日次要約) locally with [Ollama](https://ollama.com),
so users who have **not** registered a Gemini API key can still get their
transcripts analyzed on their own PC.

It mirrors the audio worker's idea (poll the server → process → return), but for
text instead of audio. The heavy lifting (prompt building, output normalization,
DB writes) stays on the **server** (`server/gemini.js`); this worker is a thin
proxy that just forwards the server-provided prompt to Ollama and returns the raw
output.

It reuses the audio worker's `../accounts.json`, so no extra login is needed if
the audio worker is already set up.

## Prerequisites

1. **Ollama** running locally:
   ```bash
   ollama serve            # if not already running as a service
   ollama pull qwen2.5:7b  # or any model good at Japanese + JSON output
   ```
2. **Accounts configured** via the audio worker's local UI (creates `client/accounts.json`).
   You can run this analyzer on the same PC as the audio worker, or on its own.

## Setup & Run

```bash
cd client
make llm-deps   # create/refresh a venv with `requests`
make run-llm    # = python client/llm/analyzer.py
```

Or directly:

```bash
python3 client/llm/analyzer.py
```

## Flow

All requests are JSON and carry `auth: {email, token}` (same accounts as the audio worker).
No PC/UUID registration is required for this worker.

1. `POST /api/llm/analyze/claim` — claims one unanalyzed transcript for the account.
   The server returns the fully-built prompt and the JSON output schema.
2. The worker calls Ollama `POST /api/generate` with that prompt and `format`=schema.
3. `POST /api/llm/analyze/result` — sends `{transcriptId, output}` (raw JSON string).
   The server parses, normalizes (deadlines etc.), and saves tasks + summary.
4. `POST /api/llm/daily/claim` / `POST /api/llm/daily/result` — same pattern for the
   daily summary (free text, no schema).

On failure (Ollama down, bad JSON), the worker reports `{error}`; the server
releases the claim lock so another attempt can pick it up later.

## Environment variables

| Variable | Default | Description |
| --- | --- | --- |
| `AIHELPER_SERVER_URL` | `http://localhost:3000` | Server URL (overridden by `baseUrl` in accounts.json) |
| `AUDIO_WORKER_CONFIG` | `client/accounts.json` | Shared account/token config (same as audio worker) |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_MODEL` | `qwen2.5:7b` | Model to use. Prefer one strong at Japanese + JSON |
| `LLM_WORKER_POLL_SEC` | `15` | Poll interval (seconds) when idle |
| `LLM_ANALYZE_TIMEOUT` | `600` | Per-call Ollama timeout (seconds) |

## Notes

- This worker only does `analyze` (task/schedule extraction + summary) and daily
  summaries. The AI chat (`/api/ask`) still uses Gemini.
- Multiple analyzer processes can run at once; the server hands out each transcript
  to only one via an atomic claim (`analysis_claimed_at`), so there is no double work.
- If both a Gemini key and this worker are available, whichever reaches an
  unanalyzed transcript first wins; results are idempotent.
