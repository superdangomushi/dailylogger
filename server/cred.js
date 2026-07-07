// 資格情報の可逆暗号化（AES-256-GCM）。
// Waseda パスワード・Google refresh_token・ユーザーごとの Gemini API キーなど、
// 平文へ戻す必要がある秘密情報の保存に使う。鍵は CRED_ENC_KEY（64桁hex）か、
// 無ければ初回起動時に .cred-key へ自動生成して以降使い回す。
// 形式は iv:tag:cipher(hex)。rotate-cred-key.js も同じ形式を前提にしている。

const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const CRED_KEY = (() => {
  const env = (process.env.CRED_ENC_KEY || "").trim();
  if (/^[0-9a-fA-F]{64}$/.test(env)) return Buffer.from(env, "hex");
  const keyFile = path.join(__dirname, ".cred-key");
  try {
    const s = fs.readFileSync(keyFile, "utf8").trim();
    if (/^[0-9a-fA-F]{64}$/.test(s)) return Buffer.from(s, "hex");
  } catch (_e) { /* 初回はファイルなし */ }
  const key = crypto.randomBytes(32);
  fs.writeFileSync(keyFile, key.toString("hex"), { mode: 0o600 });
  console.log(`資格情報の暗号鍵を生成しました: ${keyFile}`);
  return key;
})();

function encryptCred(plain) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", CRED_KEY, iv);
  const enc = Buffer.concat([cipher.update(String(plain), "utf8"), cipher.final()]);
  return `${iv.toString("hex")}:${cipher.getAuthTag().toString("hex")}:${enc.toString("hex")}`;
}

function decryptCred(stored) {
  const [ivHex, tagHex, encHex] = String(stored || "").split(":");
  if (!ivHex || !tagHex || !encHex) throw new Error("暗号データの形式が不正です");
  const decipher = crypto.createDecipheriv("aes-256-gcm", CRED_KEY, Buffer.from(ivHex, "hex"));
  decipher.setAuthTag(Buffer.from(tagHex, "hex"));
  return Buffer.concat([decipher.update(Buffer.from(encHex, "hex")), decipher.final()]).toString("utf8");
}

module.exports = { encryptCred, decryptCred };
