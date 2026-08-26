# AIHelper local audio worker

This directory is for each user's local PC. The public server in `../server`
only stores audio jobs and exposes JSON/file APIs. This client polls the server
for every enabled account configured in the local UI, downloads matching jobs,
transcribes them locally with faster-whisper, identifies owner/other speakers
with SpeechBrain ECAPA when a profile is registered, and posts the text back
to the server. It also converts owner-enrollment audio into the speaker profile.

The worker itself is a single C++ binary (`audio-worker`): all networking,
the polling loop, metrics reporting, and the local management UI are C++.
Speech recognition stays in Python (`stt/transcribe.py`, faster-whisper),
launched as a child process per job.

There is also a separate, optional **local LLM analyzer** (`llm/analyzer.py`,
Python + [Ollama](https://ollama.com)) that runs the *text* analysis locally —
課題・予定の抽出＋要約 と 日次要約 — so users without a Gemini API key can still
get their transcripts analyzed on their own PC. It reuses the same
`accounts.json`. See [llm/README.md](llm/README.md) and "Local LLM analyzer" below.

## Setup

```bash
cd client
make install
```

`make install` = `system-deps` (g++, libssl-dev, python3-venv, ffmpeg) +
`build` (compiles `audio-worker`) + `stt-deps` (faster-whisper venv).
If build tools are already installed, `make build && make stt-deps` is enough.
GPU use is automatic when faster-whisper can see CUDA. If `gpu-check` says CUDA
is not visible, CPU transcription still works.

Third-party C++ dependencies ([cpp-httplib](https://github.com/yhirose/cpp-httplib)
and [nlohmann/json](https://github.com/nlohmann/json), both MIT) are vendored in
`cpp/third_party/`, so no extra package is needed beyond OpenSSL headers.

## Run

```bash
cd client
make run        # = ./audio-worker
```

Then open the local UI:

```text
http://127.0.0.1:39123
```

The first phase after startup is **client registration** (account setup):
set the public server URL and this PC's display name, then log in with email
and password. The client generates a UUID (`clientId`) for this PC and
registers it together with the display name via `POST /api/client/register`.
Only registered accounts start processing jobs. The password is used only once
for `/api/login`; the worker stores the returned token and the generated
`clientId` in `client/accounts.json` and does not save the password.

The worker polls every enabled account every 10 seconds by default. Change it
with:

```bash
AUDIO_WORKER_POLL_SEC=5 ./audio-worker
```

## Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `AIHELPER_SERVER_URL` | `http://localhost:3000` | Public AIHelper server URL |
| `AUDIO_WORKER_UI_HOST` | `127.0.0.1` | Local UI bind host |
| `AUDIO_WORKER_UI_PORT` | `39123` | Local UI port |
| `AUDIO_WORKER_CONFIG` | `client/accounts.json` | Local account/token config file |
| `AIHELPER_EMAIL` | empty | Optional single legacy account email |
| `AIHELPER_TOKEN` | empty | Optional single legacy account token |
| `AUDIO_WORKER_POLL_SEC` | `10` | Polling interval in seconds |
| `AUDIO_WORKER_METRICS_SEC` | `3` | Metrics reporting interval in seconds |
| `AUDIO_WORKER_DIR` | `client/worker-audio` | Temporary audio download directory |
| `WHISPER_DEVICE` | auto | `cuda` or `cpu` override |
| `WHISPER_MODEL` | GPU: `large-v3`, CPU: `large-v3-turbo` | faster-whisper model |
| `WHISPER_COMPUTE` | GPU: `float16`, CPU: `int8` | faster-whisper compute type |
| `WHISPER_BATCH` | GPU: `16`, CPU: `0` | Batch size. `0` disables batched inference |
| `WHISPER_CPU_THREADS` | all cores | CPU thread count |
| `WHISPER_PYTHON` | `stt/.venv/bin/python3` | Custom Python executable |
| `SPEAKER_DEVICE` | `cpu` | SpeechBrain speaker model device (`cpu` or `cuda`) |
| `SPEAKER_MODEL` | `speechbrain/spkrec-ecapa-voxceleb` | Speaker embedding model |
| `SPEAKER_THRESHOLD` | `0.35` | Owner cosine-similarity threshold used at enrollment |

Paths default relative to the directory containing the `audio-worker` binary
(normally `client/`).

## Flow

All requests are JSON based: every request body carries the credentials
(`auth.email` / `auth.token`) and this PC's `clientId` (UUID).

1. The local UI logs in with `POST /api/login` and stores tokens per account.
2. `POST /api/client/register` registers this PC (client-generated UUID +
   user-chosen display name). This is the mandatory first phase.
3. `POST /api/client/claim` claims one queued job for each enabled account.
4. `POST /api/client/jobs/download` (`{auth, clientId, jobId}`) downloads the
   audio file as the response body.
5. For `jobType=transcription`, `client/stt/transcribe.py` runs faster-whisper and,
   when `speakerProfile` is present, labels each segment using ECAPA.
6. For `jobType=speaker_enrollment`, the same script creates a normalized ECAPA
   embedding instead of running Whisper.
7. `POST /api/client/jobs/result` sends `{ jobId, text }`,
   `{ jobId, speakerProfile }`, or `{ jobId, error }` back.

The server then saves the returned text as a transcript. If the job owner has
Gemini auto-analysis enabled, it also runs the same Gemini analysis, task
updates, cancellations, and daily-summary refresh as a normal text upload;
otherwise the user triggers analysis manually from the dashboard.

## Multiple worker PCs

You can run this worker on several PCs at the same time, even with the same
account. Each claim atomically marks exactly one queued job as `processing`,
so concurrent workers always receive different jobs and the queue is spread
across whichever PCs are idle.

The client generates its own ID (UUID) per account entry and registers it with
the server; the server rejects a UUID already owned by another account, in
which case the client regenerates and retries. Download and result requests
are only accepted from the exact client that claimed the job (matching
`clientId` owned by the authenticated account), so audio can never be fetched
by impersonating another client and results can never be attributed to the
wrong user. The legacy header/IP-based protocol was removed; old clients must
be updated.

On the server dashboard (files tab), each user can select which of their
worker PCs are allowed to process audio — multiple PCs can be checked at
once. Unchecked PCs keep polling but receive no jobs. If a stalled job is
re-queued and picked up by another PC, a late result from the original PC is
rejected instead of being saved twice.

## Local LLM analyzer (Ollama)

An optional, separate Python worker (`llm/analyzer.py`) runs the **text** analysis
locally with Ollama, so users without a Gemini API key still get task/schedule
extraction and daily summaries. It is independent from the C++ audio worker but
reuses the same `accounts.json` (no extra login).

```bash
# 1) Have Ollama running with a model pulled
ollama serve
ollama pull qwen2.5:7b

# 2) Install deps and run
cd client
make llm-deps
make run-llm      # = python llm/analyzer.py
```

Unlike the audio worker, this one needs no PC/UUID registration — it authenticates
with just the account's `email`+`token`. The server builds the prompt, and after
Ollama returns the raw JSON the server normalizes and stores it (tasks + summary),
so all the shared logic lives in `server/gemini.js`. Concurrent analyzers are safe:
each unanalyzed transcript is handed out to only one via an atomic claim
(`transcripts.analysis_claimed_at`). Full details and env vars are in
[llm/README.md](llm/README.md).
