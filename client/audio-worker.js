#!/usr/bin/env node
// 公開サーバーに置かれた音声ジョブを、このPCで処理する外部ワーカー。
//
// 必須:
//   AIHELPER_SERVER_URL=https://example.com
//   AIHELPER_EMAIL=you@example.com
//   AIHELPER_TOKEN=...
//
// 動作:
//   10秒ごとに /api/audio/worker/claim を呼び、自分のアカウントの音声だけを取得。
//   音声をダウンロードして stt/transcribe.py で文字起こしし、結果を JSON で返す。

const fs = require("fs");
const path = require("path");
const { Readable } = require("stream");
const { pipeline } = require("stream/promises");
const { localTranscribe } = require("./stt-local");

const BASE_URL = String(process.env.AIHELPER_SERVER_URL || process.env.SERVER_URL || "http://localhost:3000")
  .replace(/\/+$/, "");
const EMAIL = String(process.env.AIHELPER_EMAIL || process.env.EMAIL || "").trim();
const TOKEN = String(process.env.AIHELPER_TOKEN || process.env.TOKEN || "").trim();
const POLL_INTERVAL_MS = Math.max(Number(process.env.AUDIO_WORKER_POLL_SEC || 10), 1) * 1000;
const WORK_DIR = process.env.AUDIO_WORKER_DIR || path.join(__dirname, "worker-audio");

if (!EMAIL || !TOKEN) {
  console.error("AIHELPER_EMAIL と AIHELPER_TOKEN を設定してください。");
  process.exit(1);
}

fs.mkdirSync(WORK_DIR, { recursive: true });

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function authHeaders(extra = {}) {
  return {
    "X-Account-Email": EMAIL,
    Authorization: `Bearer ${TOKEN}`,
    ...extra,
  };
}

function url(pathname) {
  return `${BASE_URL}${pathname}`;
}

async function postJson(pathname, body = {}) {
  const res = await fetch(url(pathname), {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json", Accept: "application/json" }),
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch (_e) {
    // 下でHTTPエラーとして扱う。
  }
  if (!res.ok || !json?.ok) {
    const message = json?.error || text || `HTTP ${res.status}`;
    throw new Error(message);
  }
  return json;
}

function safeName(job) {
  const base = path.basename(job.filename || `audio-${job.id}.wav`);
  return `${job.id}-${base.replace(/[^A-Za-z0-9._-]/g, "_").slice(0, 200)}`;
}

async function downloadJobFile(job) {
  const filePath = path.join(WORK_DIR, safeName(job));
  const res = await fetch(url(job.downloadPath), {
    headers: authHeaders({ Accept: "application/octet-stream" }),
  });
  if (!res.ok) {
    const message = await res.text().catch(() => "");
    throw new Error(message || `音声ダウンロード失敗: HTTP ${res.status}`);
  }
  if (!res.body) throw new Error("音声レスポンスが空です");
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(filePath));
  return filePath;
}

async function reportError(job, error) {
  try {
    await postJson(job.resultPath, { error: String(error.message || error).slice(0, 1000) });
  } catch (e) {
    console.error(`ジョブ #${job.id} のエラー報告に失敗: ${e.message}`);
  }
}

async function processOne() {
  const claimed = await postJson("/api/audio/worker/claim");
  const job = claimed.job;
  if (!job) return false;

  let filePath = null;
  console.log(`ジョブ #${job.id} を取得: ${job.filename} (${job.quality || "high"})`);
  try {
    filePath = await downloadJobFile(job);
    const text = await localTranscribe(filePath, job.quality || "high");
    const result = await postJson(job.resultPath, { text });
    console.log(
      `ジョブ #${job.id} 完了: ${result.filename || "(本文なし)"} ` +
      `${result.chars ? `${result.chars}文字` : ""}`
    );
  } catch (e) {
    console.error(`ジョブ #${job.id} 失敗: ${e.message}`);
    await reportError(job, e);
  } finally {
    if (filePath) fs.unlink(filePath, () => {});
  }
  return true;
}

let stopping = false;
process.on("SIGINT", () => { stopping = true; });
process.on("SIGTERM", () => { stopping = true; });

(async function main() {
  console.log(`audio-worker 起動: ${BASE_URL} / ${EMAIL} / ${POLL_INTERVAL_MS / 1000}秒間隔`);
  while (!stopping) {
    try {
      const worked = await processOne();
      await sleep(worked ? 500 : POLL_INTERVAL_MS);
    } catch (e) {
      console.error(`ポーリング失敗: ${e.message}`);
      await sleep(POLL_INTERVAL_MS);
    }
  }
  console.log("audio-worker を停止します");
})();
