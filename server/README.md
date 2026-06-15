# moneybot.jp 受信サーバー (Node.js + Express + MySQL)

文字起こしアプリから送られてくるテキストを受け取り、**MySQL に直接保存**する簡易サーバー。
Web サイトにアクセスすると保存済みファイルを一覧・ダウンロードできる。

## セットアップ

```bash
cd server
npm install

# DB を用意（schema.sql で moneybot データベースと transcripts テーブルを作成）
mysql -u root < schema.sql

# 起動
npm start          # http://localhost:3000
```

接続情報は環境変数で渡す（デフォルトは下表）。

| 変数 | デフォルト | 説明 |
| --- | --- | --- |
| `DB_HOST` | `localhost` | MySQL ホスト |
| `DB_PORT` | `3306` | ポート |
| `DB_USER` | `root` | ユーザー |
| `DB_PASSWORD` | （空） | パスワード |
| `DB_NAME` | `moneybot` | データベース名 |
| `PORT` | `3000` | サーバーの待受ポート |

```bash
DB_USER=root DB_PASSWORD=secret PORT=8080 npm start
```

テーブルは起動時に `CREATE TABLE IF NOT EXISTS` で自動作成する（DB 自体は事前に作成が必要）。

## 保存先

ファイルはファイルシステムではなく `transcripts` テーブルに 1 行ずつ保存する。
`(email, filename)` がユニークキーで、同じアカウントの同じファイル名は**上書き**される
（毎時ファイルを追記して再送するケースに対応）。

| カラム | 内容 |
| --- | --- |
| `email` | 送信したアカウント |
| `filename` | ファイル名（例 `2026-06-14_15.txt`） |
| `content` | テキスト本文（LONGTEXT） |
| `created_at` / `updated_at` | 作成・更新時刻 |

## アカウント登録

`accounts.json` に「アカウント情報（email）」と「事前に作っておくトークン」を書く。
アプリ側でログインしたアカウント情報がここと一致したときだけ受け付ける。

```json
[
  { "email": "demo@moneybot.jp", "token": "demo-token-1234567890" }
]
```

## API / ページ

| メソッド | パス | 用途 |
| --- | --- | --- |
| POST | `/api/login` | アカウント情報＋トークンの照合（アプリのログイン） |
| POST | `/api/upload` | 文字起こしテキストの受信 → MySQL 保存 |
| GET | `/` | 保存済みファイルの一覧ページ（ダウンロードリンク付き） |
| GET | `/download/:id` | ファイルを `.txt` としてダウンロード |

### POST /api/upload
```
Authorization: Bearer demo-token-1234567890
X-Account-Email: demo@moneybot.jp
X-Filename: 2026-06-14_15.txt
Content-Type: text/plain

（本文 = テキストファイルの中身）
```

## 動作確認 (curl)

```bash
# 送信
curl -X POST http://localhost:3000/api/upload \
  -H 'Authorization: Bearer demo-token-1234567890' \
  -H 'X-Account-Email: demo@moneybot.jp' \
  -H 'X-Filename: test.txt' \
  -H 'Content-Type: text/plain' \
  --data-binary 'これはテスト文字起こしです'

# ブラウザで http://localhost:3000/ を開くと一覧＋ダウンロードができる
```
