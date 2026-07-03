# AIHelper local audio worker

This directory is for each user's local PC. The public server in `../server`
only stores audio jobs and exposes JSON/file APIs. This client polls the server,
downloads jobs for the same `email + token`, transcribes them locally with
faster-whisper, and posts the text back to the server.

## Setup

```bash
cd client
make install
```

If Node.js is already installed, `make stt-deps` is enough for the Whisper side.
GPU use is automatic when faster-whisper can see CUDA. If `gpu-check` says CUDA
is not visible, CPU transcription still works.

## Run

```bash
cd client
AIHELPER_SERVER_URL=https://your-server.example.com \
AIHELPER_EMAIL=demo@AIHelper.jp \
AIHELPER_TOKEN=demo-token-1234567890 \
npm start
```

The worker polls every 10 seconds by default. Change it with:

```bash
AUDIO_WORKER_POLL_SEC=5 npm start
```

## Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `AIHELPER_SERVER_URL` | `http://localhost:3000` | Public AIHelper server URL |
| `AIHELPER_EMAIL` | required | Account email. Must match `server/accounts.json` |
| `AIHELPER_TOKEN` | required | Account token. Must match `server/accounts.json` |
| `AUDIO_WORKER_POLL_SEC` | `10` | Polling interval in seconds |
| `AUDIO_WORKER_DIR` | `client/worker-audio` | Temporary audio download directory |
| `WHISPER_DEVICE` | auto | `cuda` or `cpu` override |
| `WHISPER_MODEL` | GPU: `large-v3`, CPU: `large-v3-turbo` | faster-whisper model |
| `WHISPER_COMPUTE` | GPU: `float16`, CPU: `int8` | faster-whisper compute type |
| `WHISPER_BATCH` | GPU: `16`, CPU: `0` | Batch size. `0` disables batched inference |
| `WHISPER_CPU_THREADS` | all cores | CPU thread count |
| `WHISPER_PYTHON` | `stt/.venv/bin/python3` | Custom Python executable |

## Flow

1. `POST /api/audio/worker/claim` claims one queued job for this account.
2. `GET /api/audio/worker/jobs/:id/file` downloads the audio file.
3. `client/stt/transcribe.py` transcribes the file on this PC.
4. `POST /api/audio/worker/jobs/:id/result` sends `{ "text": "..." }` back.

The server then saves the returned text as a transcript and runs the same
Gemini analysis, task updates, cancellations, and daily-summary refresh as a
normal text upload.
