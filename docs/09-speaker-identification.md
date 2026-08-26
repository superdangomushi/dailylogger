# オーナー / 他人の話者識別

登録した1人の声かどうかをPCクライアントで照合し、文字起こしの各セグメントへ
`[オーナー]` / `[他人]` を付ける。スマホは登録音声の録音と送信だけを担当し、
声紋の作成と通常音声の話者識別はどちらも `client/` のPCワーカーが行う。

## 使い方

1. AIHelperへログインし、PCで `client/audio-worker` を起動する。
2. 通常録音を停止し、録音タブの「オーナーの声」から「声を登録」を押す。
3. 普段の声量で12秒間話す。アプリはWAVを `POST /api/speaker-profile` へ送る。
4. PCが登録ジョブを処理し、表示が「登録済み」になるまで待つ。
5. 文字起こし方法を「PCクライアントで処理」にして録音する。PCが返す文字起こしに話者ラベルが付く。

再登録は「声を再登録」、声紋と登録待ち音声の削除は「削除」から行う。

## 処理の流れ

```text
スマホ: 12秒のPCM録音
  → POST /api/speaker-profile
サーバー: audio_jobs(job_type=speaker_enrollment, queued)
  → claim / download
PCクライアント: SpeechBrain ECAPA-TDNNで3秒窓ごとの埋め込みを作成
  → 平均・L2正規化した speakerProfile を result で返す
サーバー: AES-256-GCM暗号化後 speaker_profiles へ保存
  → 登録WAVを削除

スマホ: 通常録音を POST /api/audio
サーバー: ジョブと復号した speakerProfile を、claimしたPCに限定して渡す
PCクライアント: faster-whisper のセグメント時刻ごとに ECAPA 埋め込みを作成
  → 登録埋め込みとのコサイン類似度で [オーナー] / [他人]
  → ラベル付きテキストを POST /api/client/jobs/result
```

SpeechBrainは既定でCPUを使う。WhisperのCUDA VRAMと競合させたくないためで、
`SPEAKER_DEVICE=cuda` の指定時だけGPUを使う。モデルとしきい値は
`SPEAKER_MODEL` / `SPEAKER_THRESHOLD` で上書きできる。

## データとプライバシー

- スマホ内では声紋特徴を作成・保存しない。登録PCMは送信後にメモリから破棄する。
- 登録WAVはサーバーから、ジョブをclaimした認証済みPCだけが取得できる。成功時に削除する。
- 処理が失敗したWAVは再試行用に保持する。アプリの「削除」は失敗/待機/処理中の登録ジョブとWAVも削除する。
- 保存する声紋は正規化済み埋め込みとしきい値だけで、`cred.js` の AES-256-GCM で暗号化する。
- 声紋はジョブ所有者の通常音声と一緒に、そのジョブをclaimしたPCにだけ渡す。
- これは文字起こし用の推定であり、ログインや本人確認には使わない。

## 実装場所

| 場所 | ファイル | 役割 |
| --- | --- | --- |
| Android | `speaker/OwnerVoiceEnrollmentRecorder.kt`, `net/AiHelperClient.kt` | 12秒録音とWAV送信 |
| iOS | `UI/RecordingTabView.swift`, `Net/AiHelperClient.swift` | 12秒録音とWAV送信 |
| サーバー | `server/audio.js`, `server/db.js`, `server/server.js` | ジョブ管理、暗号化保存、API |
| PCクライアント | `client/cpp/src/worker.hpp`, `client/cpp/src/stt.hpp` | 登録/通常ジョブ分岐とPython実行 |
| PCクライアント | `client/stt/transcribe.py` | ECAPA声紋作成・照合、Whisperラベル付与 |

## 現在の制約

- 「登録した1人か、それ以外か」の照合であり、複数の他人をA/B/Cに分ける話者ダイアライゼーションではない。
- Whisperセグメント内で話者が交代・重複した場合、そのセグメントには1つのラベルしか付かない。
- 録音距離、マイク、騒音、マスク、体調が登録時と大きく異なると誤判定しやすい。
- 端末内Whisperモードには話者ラベルが付かない。
