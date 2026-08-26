# オーナー / 他人の話者識別

スマホへオーナーの声を一度登録し、端末内 Whisper の文字起こしへ
`[オーナー]` / `[他人]` のラベルを付ける機能。Android と iOS の両方に同じ処理を実装している。

## 使い方

1. 通常の録音を停止する。
2. 録音タブの「オーナーの声」で「声を登録」を押す。
3. 画面の例文を参考に、普段の声量で12秒間続けて話す。
4. 「登録済み（話者識別 ON）」になったことを確認し、文字起こし方法を「端末で処理」にして録音する。

再登録は同じカードの「声を再登録」、無効化は「削除」。登録・削除は通常録音中にはできない。

## データとプライバシー

- 登録中の PCM はメモリ上だけに保持し、声紋作成後に破棄する。
- 保存するのは3秒ごとの正規化済み音響特徴（MFCCの平均/分散、基本周波数、スペクトル重心、
  ゼロ交差率、スペクトル平坦度）と照合しきい値だけ。
- Android は `noBackupFilesDir/owner-voice-profile.json`、iOS はバックアップ除外属性を付けた
  `Application Support/OwnerVoice/owner-voice-profile.json` に保存する。
- 声紋や登録音声をサーバーへ送らない。この声紋は本人確認・ロック解除などの
  セキュリティ用途を想定していない。

## 処理の流れ

```text
12秒の登録音声
  → 3秒 × 4区間
  → 有声フレームだけから声紋を抽出
  → 区間間のばらつきに合わせて照合しきい値を決定
  → 端末のバックアップ対象外領域へ特徴量だけ保存

通常録音（端末文字起こし）
  → 声紋未登録: 従来どおり30秒窓で Whisper
  → 声紋登録済み: 10秒窓で Whisper + 声紋照合
  → 同じ判定が続く窓をまとめて [オーナー] / [他人] を付与
  → transcripts/*.txt へ保存 → 従来どおりサーバー同期
```

無音や有声フレームが足りない窓は `[話者不明]`。登録時に十分な発話を3区間以上
検出できなければ、環境音を誤って登録しないよう登録を失敗させる。

## 実装場所

| プラットフォーム | ファイル | 役割 |
| --- | --- | --- |
| Android | `app/src/main/java/com/ishilab/transcriber/speaker/OwnerVoiceProfile.kt` | 特徴抽出、声紋、照合、端末保存 |
| Android | `app/src/main/java/com/ishilab/transcriber/speaker/OwnerVoiceEnrollmentRecorder.kt` | 12秒の登録録音 |
| Android | `app/src/main/java/com/ishilab/transcriber/service/AudioCaptureService.kt` | 10秒窓の判定とラベル付与 |
| Android | `app/src/main/java/com/ishilab/transcriber/RecordingTabUi.kt` | 登録・再登録・削除UI |
| iOS | `ios/Transcriber/Audio/AudioChunker.swift` | 特徴抽出、声紋、照合、端末保存 |
| iOS | `ios/Transcriber/UI/RecordingTabView.swift` | 登録録音とUI |
| iOS | `ios/Transcriber/Audio/AudioCaptureService.swift` | 10秒窓の判定とラベル付与 |

Android の特徴抽出には、無音拒否・同一声質の一致・異なる疑似声質の不一致を確認する
`OwnerVoiceProfileTest` がある。

## 現在の制約

- サーバー文字起こしモードは音声を1時間単位で外部ワーカーへ送るため、話者ラベルは付かない。
- これは「登録した1人か、それ以外か」の照合で、複数の他人をA/B/Cに分ける話者ダイアライゼーションではない。
- 10秒窓の途中で話者が交代・重複した場合、その窓を代表する声へ1つのラベルを付ける。
- 録音距離、マイク、騒音、マスク、体調が登録時と大きく異なると誤判定しやすい。
  実際に使う場所と近い条件で再登録すると改善する。
