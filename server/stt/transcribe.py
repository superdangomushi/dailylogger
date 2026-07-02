#!/usr/bin/env python3
"""ローカル文字起こし（faster-whisper）。

使い方: transcribe.py <音声ファイル>
本文だけを stdout に出力する（進捗・ログは stderr）。audio.js から子プロセスとして呼ばれる。

環境変数:
  WHISPER_MODEL   ... モデル名（既定 large-v3-turbo。軽くしたければ medium / small）
  WHISPER_COMPUTE ... compute_type（既定 int8。CPU 前提）
初回実行時にモデルを ~/.cache/huggingface へ自動ダウンロードする（turbo で約 1.6GB）。
"""

import os
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("使い方: transcribe.py <音声ファイル>", file=sys.stderr)
        return 2
    path = sys.argv[1]
    if not os.path.exists(path):
        print(f"ファイルがありません: {path}", file=sys.stderr)
        return 2

    from faster_whisper import WhisperModel

    model_name = os.environ.get("WHISPER_MODEL", "large-v3-turbo")
    compute = os.environ.get("WHISPER_COMPUTE", "int8")
    print(f"モデル {model_name} ({compute}) を読み込み中…", file=sys.stderr)
    model = WhisperModel(model_name, device="auto", compute_type=compute)

    # vad_filter で無音区間を飛ばす（ゼミ録音の長い沈黙対策 + 幻覚抑制）。
    segments, info = model.transcribe(path, language="ja", vad_filter=True)
    for seg in segments:
        text = seg.text.strip()
        if text:
            print(text, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
