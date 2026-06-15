// MySQL 接続まわり。接続情報は環境変数で渡す。
const mysql = require("mysql2/promise");

const pool = mysql.createPool({
  host: process.env.DB_HOST || "localhost",
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || "root",
  password: process.env.DB_PASSWORD || "",
  database: process.env.DB_NAME || "moneybot",
  waitForConnections: true,
  connectionLimit: 5,
  charset: "utf8mb4",
});

// 起動時にテーブルが無ければ作る（DB 自体は事前に作成しておく前提）。
async function ensureSchema() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS transcripts (
      id         INT AUTO_INCREMENT PRIMARY KEY,
      email      VARCHAR(255) NOT NULL,
      filename   VARCHAR(255) NOT NULL,
      content    LONGTEXT     NOT NULL,
      created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
      updated_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
      UNIQUE KEY uq_email_filename (email, filename)
    ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
  `);
}

// テキストを保存（同じ email + filename は上書き）。
async function saveTranscript(email, filename, content) {
  await pool.query(
    `INSERT INTO transcripts (email, filename, content)
     VALUES (?, ?, ?)
     ON DUPLICATE KEY UPDATE content = VALUES(content), updated_at = CURRENT_TIMESTAMP`,
    [email, filename, content]
  );
}

// 一覧（中身は含めない。サイズと更新時刻だけ）。
async function listTranscripts() {
  const [rows] = await pool.query(
    `SELECT id, email, filename, CHAR_LENGTH(content) AS chars, updated_at
     FROM transcripts
     ORDER BY updated_at DESC, id DESC`
  );
  return rows;
}

// 1 件を中身ごと取得。
async function getTranscript(id) {
  const [rows] = await pool.query(
    `SELECT id, email, filename, content FROM transcripts WHERE id = ? LIMIT 1`,
    [id]
  );
  return rows[0] || null;
}

module.exports = { pool, ensureSchema, saveTranscript, listTranscripts, getTranscript };
