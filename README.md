# ishilab_zemi
研究室のゼミ課題(みんな見てね！！)

## 常時録音・ローカル文字起こし Android アプリ

バックグラウンドで常時録音し、端末上（ローカル / whisper.cpp）で文字起こしを続け、
**1時間ごとに1つのテキストファイル**として出力する Android アプリ。
通知バーのボタンからマイク利用を一時停止/再開できる。

### 仕組み（概要）
- **AudioCaptureService**（フォアグラウンドサービス, type=microphone）が常時録音
  - 16kHz/mono PCM を 30 秒チャンクに分割 → whisper.cpp で逐次文字起こし
  - 結果は `filesDir/transcripts/YYYY-MM-DD_HH.txt` に追記（= 1時間1ファイル）
  - 文字起こし済みの音声データは破棄（テキストのみ保存）
  - 簡易VADでほぼ無音のチャンクはスキップし負荷を軽減
  - 画面OFFでも継続するため PARTIAL_WAKE_LOCK を保持
- **通知**: [一時停止/再開]（マイクを完全解放/再取得）・[終了]
- **モデル**: 初回起動時に ggml モデル(tiny/base/small)をダウンロード。以降はオフライン動作
- whisper.cpp は git submodule（`app/src/main/cpp/whisper.cpp`, v1.7.4）として取り込み、
  NDK/CMake でビルドして単一の `libwhisper-jni.so` にまとめる

### 主要ファイル
| 役割 | パス |
| --- | --- |
| 録音/文字起こしサービス | `app/src/main/java/com/ishilab/transcriber/service/AudioCaptureService.kt` |
| 通知制御の受信 | `app/.../service/MicControlReceiver.kt` |
| チャンク化・簡易VAD | `app/.../audio/AudioChunker.kt` |
| whisper エンジン / 抽象 | `app/.../transcribe/WhisperEngine.kt`, `TranscriptionEngine.kt` |
| 出力ファイル管理 | `app/.../transcribe/TranscriptStore.kt` |
| モデルDL管理 | `app/.../model/ModelManager.kt` |
| JNI ブリッジ | `app/.../whisper/WhisperLib.kt`, `app/src/main/cpp/{jni.c,CMakeLists.txt}` |
| 画面 | `app/.../MainActivity.kt`, `app/.../ui/MainViewModel.kt` |

### ビルド手順
NDK 27 / CMake 3.22 / JDK 17 が必要。
```bash
# submodule（whisper.cpp 本体）を取得
git submodule update --init --recursive

# JDK 17 を指定してビルド
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :app:assembleDebug
# 生成物: app/build/outputs/apk/debug/app-debug.apk
```

### 使い方
1. アプリ起動 → マイク/通知の権限を許可
2. 初回はモデルをダウンロード（日本語は base 以上を推奨）
3. 「録音開始」→ 通知が常駐し、文字起こしが `transcripts/` に蓄積される
4. マイクを一時的に手放したいときは通知の「一時停止」、戻すときは「再開」

### moneybot.jp への送信
文字起こしファイルを `moneybot.jp` に送れる。事前に moneybot.jp 側でアカウントに
**トークン**を発行しておき、アプリでそのアカウント情報＋トークンでログインする。
サーバー側でアカウント情報とトークンの一致を確認できたときだけファイルが受け付けられる。

- アプリ画面の「moneybot.jp 連携」でサーバーURL・アカウント(メール)・トークンを入力しログイン
- 各文字起こしファイルの「moneybotへ送信」ボタンで `POST /api/upload` に本文を送信
- 通信は追加ライブラリなし（`HttpURLConnection` + `org.json`）。本番は HTTPS、ローカル動作
  確認用に `10.0.2.2` / `localhost` などへの平文 HTTP のみ許可（`res/xml/network_security_config.xml`）

| 役割 | パス |
| --- | --- |
| 送信クライアント | `app/.../net/MoneybotClient.kt` |
| ログイン情報の保存 | `app/.../net/AccountStore.kt` |

受信側の簡易サーバー（Node.js + Express + MySQL）は `server/` にある。受け取った
テキストは **MySQL に直接保存**し、Web サイト（`/`）から一覧・ダウンロードできる。
`accounts.json` にアカウントとトークンを登録、`mysql -u root < schema.sql` で DB を
用意して `npm install && npm start` で起動。詳細は `server/README.md`。

### 注意
- 常時録音＋推論はバッテリー/発熱の負荷が大きい。端末の電池最適化からの除外を推奨。
- 端末再起動後の自動再開・話者分離・音声保存は対象外（必要なら拡張可能）。
- moneybot.jp 連携はゼミ課題向けの簡易実装（トークン平文保持・HTTP単純送信）。
