# client/ コードマップ（ワーカーPC側）

ワーカーPCで動かすクライアント一式。サーバーの音声ジョブをポーリングし、
ローカルの faster-whisper で文字起こしして結果を返す。

本体は C++ の単一バイナリ `audio-worker`（通信・ポーリング・メトリクス・ローカル管理UI）。
音声認識だけは従来どおり Python（`stt/transcribe.py`）を子プロセスで呼ぶ。
JS版（`audio-worker.js` / `stt-local.js`）は 2026-07 に C++ へ置き換えた。
設定ファイル `accounts.json` の形式とサーバーAPIの契約は JS版と完全互換。

## ファイル一覧

| ファイル | 役割 |
| --- | --- |
| `cpp/src/main.cpp` | エントリポイント。環境変数の読込、スレッド起動（UI / メトリクス / ワーカー）、シグナル処理 |
| `cpp/src/worker.hpp` | ジョブ処理の本体。登録フェーズ・claim・download・result・メトリクス送信ループ |
| `cpp/src/api.hpp` | サーバー通信（`post_json` / `login_with_password` / `download_job_file`）。リダイレクト検出つき |
| `cpp/src/ui.hpp` | ローカル管理UIのルート（`/api/state` / `/api/settings` / `/api/accounts` …） |
| `cpp/src/ui_html.hpp` | 管理画面HTML（静的1枚。データはページ内JSが `/api/state` から取得） |
| `cpp/src/config.hpp` | `accounts.json` の読み書き（アトミック保存 mode 0600）と実行状態。全スレッド共有・mutex保護 |
| `cpp/src/metrics.hpp` | CPU（/proc/stat差分）/ メモリ（/proc/meminfo）/ GPU（nvidia-smi）のサンプリング |
| `cpp/src/stt.hpp` | `stt/transcribe.py` の子プロセス起動（fork/exec + pipe、2時間タイムアウト） |
| `cpp/src/util.hpp` | 時刻ISO文字列・UUID v4生成・文字列処理などの小物 |
| `cpp/third_party/` | vendor した cpp-httplib / nlohmann-json（ともにMIT、単一ヘッダ） |
| `stt/transcribe.py` | faster-whisper 実行本体（Python。`stt/.venv` 内で動く） |
| `accounts.json` | 設定ファイル（git管理外）。下記参照 |
| `Makefile` | `make install` / `make build`（C++ビルド）/ `make stt-deps`（Python venv構築）/ `make gpu-check` |
| `worker-audio/` | ダウンロードした音声の一時置き場（処理後すぐ削除） |

ビルドは `make build`（g++ -std=c++17、要 `libssl-dev`）。生成物は `client/audio-worker`。

## accounts.json の形式（実例）

```json
{
  "baseUrl": "https://aihelper.example.com",
  "mode": "private",
  "clientName": "研究室デスクトップ",
  "accounts": [
    {
      "email": "user@example.com",
      "token": "691ff8ca9ac063e6caa6f0bd952869212d198da27f1c55ed",
      "enabled": true,
      "source": "ui",
      "clientId": "41685d34-86c5-42be-a598-e4985d8d43f4",
      "registered": true,
      "addedAt": "2026-07-13T09:18:28.500Z",
      "updatedAt": "2026-07-13T09:18:28.512Z"
    }
  ]
}
```

| キー | 意味 |
| --- | --- |
| `baseUrl` | 公開サーバーURL |
| `mode` | このPCの公開範囲。`private`（自分のジョブのみ）/ `global`（全ユーザーのジョブを処理） |
| `clientName` | **ユーザーが決めるこのPCの表示名**。サーバーのPC選択画面に出る。未設定ならホスト名 |
| `accounts[].token` | `/api/login` で得たAPIトークン。**パスワードは保存しない** |
| `accounts[].clientId` | **クライアントが自動生成したこのPCのID（UUID）**。アカウントごとに1つ |
| `accounts[].registered` | サーバーへのクライアント登録（`/api/client/register`）が済んでいるか |

旧形式（`workerId` / `workerName` を持つもの）を読み込んだ場合、`clientId` が無いため
`registered=false` となり、起動後の最初のポーリングで自動的に登録フェーズをやり直す。

## 処理の構造

### 設定・状態（config.hpp）

- 環境変数から定数化: `AIHELPER_SERVER_URL` / `AUDIO_WORKER_CONFIG` /
  `AUDIO_WORKER_POLL_SEC`(10) / `AUDIO_WORKER_METRICS_SEC`(3) /
  `AUDIO_WORKER_UI_PORT`(39123) / `AUDIO_WORKER_DIR`（main.cpp で読込）
- `load_config()` — accounts.json + 環境変数（`AIHELPER_EMAIL`/`AIHELPER_TOKEN`）のマージ
- `normalize_account()` — clientId の形式検証と旧形式の移行
- `save_config_locked()` — tmpファイル経由のアトミック保存（mode 0600）
- `update_status` / `g_runtime` — アカウント毎の実行状態（UIに表示）
- `public_state()` — ローカルUIの `/api/state` が返す内容
- `auth_body()`（api.hpp）— 全リクエスト共通のJSONボディ（`auth` + `clientId`）を作る
- UI/ワーカー/メトリクスの3スレッドが共有するため、すべて `g_state_mutex` で保護。
  ネットワークI/O中はロックを持たない（アカウントはスナップショットのコピーで処理）

### メトリクス（metrics.hpp + worker.hpp の metrics_loop）

- CPU（/proc/stat 差分）/ メモリ（/proc/meminfo）/ GPU（nvidia-smi、無ければnull）のサンプリング
- `metrics_loop()` — 3秒ごとに `POST /api/client/metrics`。**登録済みアカウントのみ送る**。
  処理中ジョブのIDをハートビートとして同送。`unregistered` エラーで登録フラグを落とす

### 通信（api.hpp）

- 全リクエストでリダイレクト検出（POSTがGETに化ける事故の防止。http→https転送プロキシ対策）
- `login_with_password(email, password)` — `/api/login`
- `post_json(account, path, body)` — auth_bodyを合成してPOST。サーバーの `code`
  （unregistered等）を `ApiError.code` に引き継ぐ
- `download_job_file(account, jobId, path)` — `POST /api/client/jobs/download`
  （JSON→バイナリ）。content_receiver でファイルへ直接ストリーム書き込み
- `report_error`（worker.hpp）— 失敗を `jobs/result` に `{jobId, error}` で報告。
  サーバーは上限（既定3回）までは即 queued に戻して再割り振りするので、クライアント側の追加対応は不要

### 登録フェーズとジョブ処理（worker.hpp）

| 関数 | 内容 |
| --- | --- |
| `register_account(acc)` | clientId が無ければ UUID v4 を生成 → `POST /api/client/register`。`uuid_conflict`(409) なら再生成して最大3回リトライ |
| `ensure_registered(acc)` | 未登録なら登録フェーズを済ませてから処理へ進む（**毎ポーリングの1行目**） |
| `mark_unregistered(email)` | サーバー側で登録が消えた（PC削除等）ときにフラグを落として再登録に回す |
| `process_one(acc)` | 1アカウント分の1周: ensure_registered → claim → (job無ければ待機) → download → `stt::local_transcribe` → result送信。失敗時は report_error |
| `worker_loop()` | 全有効アカウントを順に process_one。仕事があったら0.5秒後、無ければ10秒後に次周 |

### ローカル管理UI（ui.hpp / ui_html.hpp）

cpp-httplib の `Server` で 127.0.0.1:39123 に立つ。認証なし（ローカルのみバインド）。

| ルート | 内容 |
| --- | --- |
| `GET /` | 管理画面HTML（`ui_html.hpp` の静的ページ） |
| `GET /api/state` | 現在の設定+アカウント状態（`public_state()`） |
| `POST /api/settings` | `{baseUrl?, clientName?, mode?}` の部分更新。**clientName が変わったら登録済みアカウントを自動再登録**（表示名はregister経由でしか変わらないため） |
| `POST /api/accounts` | **初回セットアップの本体**。`{email, password, baseUrl?, clientName?}` → `/api/login` → UUID生成 → `/api/client/register`。レスポンス例は下記 |
| `PATCH /api/accounts/:email` | `{enabled}` の切替 |
| `DELETE /api/accounts/:email` | アカウント削除 |

`POST /api/accounts` のレスポンス例:

```json
{
  "ok": true,
  "email": "user@example.com",
  "registered": true,
  "clientId": "41685d34-86c5-42be-a598-e4985d8d43f4",
  "registerError": null
}
```

登録に失敗してもアカウントは保存され（`registered: false`）、次のポーリングで自動再試行される。

## stt.hpp / transcribe.py

- `stt::local_transcribe(filePath, quality)` が `stt/.venv/bin/python3 stt/transcribe.py <file>`
  を fork/exec で起動（`WHISPER_PYTHON` で python を上書き可）。
- quality（`light` / `standard` / `high`）は**ジョブ所有者**のサーバー側設定（`users.stt_quality`）から
  claim レスポンスで届き、環境変数（`WHISPER_MODEL` 等）に変換して子プロセスへ渡す。
- venv が無い場合は「ローカル文字起こしが未設定です（`make stt-deps` を実行してください）」を投げ、
  それがそのままサーバーへエラー報告される。
- タイムアウト2時間（超過で SIGKILL）。失敗時は stderr の末尾2行をエラーメッセージに添える。

## 起動から処理開始までのタイムライン

```
make run（= ./audio-worker）
 ├─ UIスレッド            … 管理UI (127.0.0.1:39123) 起動
 ├─ metrics_loop スレッド … 3秒ごと（登録済みアカウントのみ送信）
 └─ worker_loop（メイン） … 10秒ごと
     └─ process_one(account)
         ├─ ensure_registered()  ← 未登録ならここで登録フェーズ
         │    └─ POST /api/client/register （409なら UUID再生成）
         ├─ POST /api/client/claim
         ├─ POST /api/client/jobs/download → WAV保存
         ├─ transcribe.py（faster-whisper）
         └─ POST /api/client/jobs/result
```
