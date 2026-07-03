const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const STT_DIR = path.join(__dirname, "stt");

// ローカル文字起こしに使う python。WHISPER_PYTHON で上書きでき、
// 既定は stt/.venv（make stt-deps が作る）の python。
function sttPython() {
  if (process.env.WHISPER_PYTHON) return process.env.WHISPER_PYTHON;
  const venv = path.join(STT_DIR, ".venv", "bin", "python3");
  return fs.existsSync(venv) ? venv : null;
}

// アカウントの音声認識クオリティ → transcribe.py への環境変数上書き。
// high は従来どおりの自動判定（GPU なら large-v3 の精度最優先）。
const QUALITY_ENV = {
  light: { WHISPER_MODEL: "small", WHISPER_BEAM_SIZE: "5", WHISPER_BEST_OF: "5", WHISPER_PATIENCE: "1.0" },
  standard: { WHISPER_MODEL: "large-v3-turbo" },
  high: {},
};

// stt/transcribe.py を子プロセスで実行し、stdout の本文を返す。
function localTranscribe(filePath, quality) {
  return new Promise((resolve, reject) => {
    const py = sttPython();
    if (!py) {
      return reject(new Error(
        "ローカル文字起こしが未設定です（`make stt-deps` を実行してください）"
      ));
    }
    const env = { ...process.env, ...(QUALITY_ENV[quality] || {}) };
    const child = spawn(py, [path.join(STT_DIR, "transcribe.py"), filePath], { env });
    let out = "";
    let err = "";
    child.stdout.on("data", (c) => { out += c.toString(); });
    child.stderr.on("data", (c) => { err = (err + c.toString()).slice(-2000); });
    child.on("error", (e) => reject(new Error(`文字起こしを起動できません: ${e.message}`)));
    // 長時間録音対策のタイムアウト（2時間）。
    const timer = setTimeout(() => child.kill("SIGKILL"), 2 * 3600_000);
    child.on("close", (code) => {
      clearTimeout(timer);
      if (code === 0) return resolve(out.trim());
      const tail = err.trim().split("\n").filter(Boolean).slice(-2).join(" / ");
      reject(new Error(`文字起こし失敗 (exit ${code})${tail ? `: ${tail}` : ""}`));
    });
  });
}

module.exports = { localTranscribe, sttPython };
