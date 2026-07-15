// 認証まわりのヘルパ（accounts.json・パスワードハッシュ・トークン照合・ログイン試行制限）。
// server.js から分割。
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const db = require("./db");

const ACCOUNTS_FILE = path.join(__dirname, "accounts.json");

// accounts.json はリクエストのたびに読み直す（編集してすぐ反映できるように）。
function loadAccounts() {
  try {
    return JSON.parse(fs.readFileSync(ACCOUNTS_FILE, "utf8"));
  } catch (e) {
    if (e.code === "ENOENT") return [];
    console.error("accounts.json の読み込みに失敗:", e.message);
    return [];
  }
}

// ---- 自己登録ユーザー（MySQL 保存・scrypt ハッシュ） ----
// 既存の sha256(salt + password) 形式はログイン時だけ互換検証し、成功時に scrypt へ移行する。
function sha256(salt, password) {
  return crypto.createHash("sha256").update(salt + String(password)).digest("hex");
}
function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString("hex");
  const n = 16384;
  const r = 8;
  const p = 1;
  const derived = crypto.scryptSync(String(password), salt, 64, { N: n, r, p }).toString("hex");
  return { salt, hash: `scrypt$${n}$${r}$${p}$${salt}$${derived}` };
}
function timingSafeHexEqual(a, b) {
  if (!/^[0-9a-f]+$/i.test(a || "") || !/^[0-9a-f]+$/i.test(b || "")) return false;
  const ab = Buffer.from(a, "hex");
  const bb = Buffer.from(b, "hex");
  return ab.length === bb.length && crypto.timingSafeEqual(ab, bb);
}
function verifyPassword(user, password) {
  const stored = String(user?.password_hash || "");
  if (stored.startsWith("scrypt$")) {
    const [, n, r, p, salt, expected] = stored.split("$");
    if (!n || !r || !p || !salt || !expected) return { ok: false, legacy: false };
    const actual = crypto.scryptSync(String(password), salt, 64, {
      N: Number(n), r: Number(r), p: Number(p),
    }).toString("hex");
    return { ok: timingSafeHexEqual(actual, expected), legacy: false };
  }
  return { ok: stored === sha256(user.salt, password), legacy: true };
}
function genToken() {
  return crypto.randomBytes(24).toString("hex"); // 48 hex chars
}

// email + token を accounts.json → DB の順で照合し、アカウント相当を返す（非同期）。
async function resolveAccount(email, token) {
  if (!email || !token) return null;
  const acc = loadAccounts().find((a) => a.email === email && a.token === token);
  if (acc) return acc;
  const u = await db.getUserByToken(email, token);
  return u ? { email: u.email, token: u.token, lineUserId: "" } : null;
}

// email から LINE の送信先 userId を引く（リマインドエンジンが使う）。
function resolveLineTarget(email) {
  const a = loadAccounts().find((x) => x.email === email);
  return a ? a.lineUserId || "" : "";
}

// ---- ログイン試行のレート制限（総当たり対策） ----
// パスワードは sha256 保存とはいえ、試行回数に制限が無いと弱いパスワードを
// オンラインで総当たりされうる。IP ごとに直近の失敗回数を数え、一定回数を超えたら
// 短時間ロックする（メモリ管理。サーバー再起動でリセットされるが実害は小さい）。
const LOGIN_MAX_FAILS = Number(process.env.LOGIN_MAX_FAILS || 10);
const LOGIN_LOCK_MS = Number(process.env.LOGIN_LOCK_SEC || 900) * 1000; // 既定15分
const loginAttempts = new Map(); // key -> { fails, firstAt, lockedUntil }

function loginRateKey(req) {
  // プロキシ配下では X-Forwarded-For の先頭が実 IP。無ければ接続元。
  const fwd = (req.headers["x-forwarded-for"] || "").split(",")[0].trim();
  return fwd || req.socket?.remoteAddress || "unknown";
}

// true を返したらブロック（レスポンスは呼び出し側で返す）。
function isLoginBlocked(req) {
  const rec = loginAttempts.get(loginRateKey(req));
  return Boolean(rec && rec.lockedUntil && rec.lockedUntil > Date.now());
}

function recordLoginFailure(req) {
  const key = loginRateKey(req);
  const now = Date.now();
  const rec = loginAttempts.get(key) || { fails: 0, firstAt: now, lockedUntil: 0 };
  // 前回ロックが切れていれば数え直す。
  if (rec.lockedUntil && rec.lockedUntil <= now) {
    rec.fails = 0;
    rec.firstAt = now;
    rec.lockedUntil = 0;
  }
  rec.fails += 1;
  if (rec.fails >= LOGIN_MAX_FAILS) rec.lockedUntil = now + LOGIN_LOCK_MS;
  loginAttempts.set(key, rec);
  // 溜まった古いレコードを掃除。
  if (loginAttempts.size > 5000) {
    for (const [k, v] of loginAttempts) {
      if ((v.lockedUntil || v.firstAt) < now - LOGIN_LOCK_MS) loginAttempts.delete(k);
    }
  }
}

function recordLoginSuccess(req) {
  loginAttempts.delete(loginRateKey(req));
}

// API 用の認証ヘルパ。ヘッダ（推奨）または互換用 JSON body から email+token を取り、照合する（非同期）。
async function authFromReq(req) {
  const email = req.get("X-Account-Email") || req.body?.email || "";
  const token =
    (req.get("Authorization") || "").replace(/^Bearer\s+/i, "") ||
    req.body?.token ||
    "";
  return resolveAccount(email, token);
}

// ワーカークライアント用: JSON ボディの auth からアカウントを照合する。
async function authFromJsonBody(req) {
  const a = req.body?.auth || {};
  return resolveAccount(String(a.email || "").trim(), String(a.token || "").trim());
}

module.exports = {
  ACCOUNTS_FILE,
  loadAccounts,
  hashPassword,
  verifyPassword,
  genToken,
  resolveAccount,
  resolveLineTarget,
  isLoginBlocked,
  recordLoginFailure,
  recordLoginSuccess,
  authFromReq,
  authFromJsonBody,
};
