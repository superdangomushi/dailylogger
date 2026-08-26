#!/usr/bin/env python3
"""ローカル文字起こし（faster-whisper。GPU があれば自動で使う）。

使い方:
  transcribe.py <音声ファイル> [--speaker-profile JSON]
  transcribe.py --enroll-speaker <登録音声>
通常モードは本文だけ、登録モードは話者プロファイルJSONだけを stdout に出力する
（進捗・ログは stderr）。C++ワーカーから子プロセスとして呼ばれる。

既定値は「GPU なら精度最優先・CPU なら現実的な速度」になるよう自動で決まる:
  GPU (CUDA):  モデル large-v3 / compute float16 / バッチ推論 (batch=16)
  CPU:         モデル large-v3-turbo / compute int8 / 全コア使用
デコードも beam_size=10 / best_of=10 / patience=2.0 とデフォルトより広く探索し、
速度よりも精度を優先する（リソースをじゃぶじゃぶ使ってでも高精度にする方針）。

GPU の空き VRAM は他アプリ（デスクトップ環境等）や録音の長さで変動し、精度優先設定のまま
確保できず CUDA out of memory になることがある。その場合はバッチ→ビーム探索の順に設定を
軽くしながら自動で再試行し、それでも駄目なら CPU（large-v3-turbo/int8）にフォールバックする
ため、この関数がクラッシュして音声文字起こしジョブが失敗することはない。

環境変数（すべて任意。未設定なら上の自動判定）:
  WHISPER_DEVICE      ... cuda | cpu（既定: 自動判定）
  WHISPER_MODEL       ... モデル名（例 large-v3 / large-v3-turbo / medium）
  WHISPER_COMPUTE     ... compute_type（例 float16 / int8_float16 / int8）
  WHISPER_BATCH       ... バッチサイズ。0 でバッチ推論を無効化
  WHISPER_CPU_THREADS ... CPU 時のスレッド数（既定: 全コア）
  WHISPER_BEAM_SIZE   ... ビームサーチ幅（既定: 10）
  WHISPER_BEST_OF     ... サンプリング候補数（既定: 10）
  WHISPER_PATIENCE    ... ビーム探索の打ち切り猶予（既定: 2.0）
  SPEAKER_DEVICE      ... 話者埋め込みの cpu | cuda（既定: cpu）
  SPEAKER_MODEL       ... SpeechBrainモデル（既定: spkrec-ecapa-voxceleb）
  SPEAKER_THRESHOLD   ... オーナー判定のコサイン類似度しきい値（既定: 0.35）
  SPEAKER_BATCH       ... 話者照合のバッチサイズ（既定: 16）

初回実行時にモデルを ~/.cache/huggingface へ自動ダウンロードする（large-v3 で約 3GB）。
GPU 用の cuDNN/cuBLAS は pip（stt/requirements.txt）で入り、下の _ensure_cuda_libs が
LD_LIBRARY_PATH へ自動で通すので、CUDA Toolkit のシステムインストールは不要
（NVIDIA ドライバだけあればよい。導入は `make gpu-driver`）。
"""

import glob
import hashlib
import json
import os
import site
import sys


def _ensure_cuda_libs():
    """pip で入れた nvidia-cudnn/cublas の .so を dlopen できるようにする。

    LD_LIBRARY_PATH は起動時にしか読まれないため、不足していれば足して自分を再実行する。
    再実行後は不足がなくなるので無限ループにはならない。
    """
    dirs = []
    for sp in site.getsitepackages():
        dirs += glob.glob(os.path.join(sp, "nvidia", "*", "lib"))
    if not dirs:
        return
    current = os.environ.get("LD_LIBRARY_PATH", "")
    missing = [d for d in dirs if d not in current.split(":")]
    if not missing:
        return
    os.environ["LD_LIBRARY_PATH"] = ":".join(missing + ([current] if current else []))
    os.execv(sys.executable, [sys.executable] + sys.argv)


def _pick_device():
    forced = os.environ.get("WHISPER_DEVICE", "").strip().lower()
    if forced in ("cuda", "cpu"):
        return forced
    try:
        import ctranslate2
        if ctranslate2.get_cuda_device_count() > 0:
            return "cuda"
    except Exception as e:
        print(f"CUDA 判定に失敗（CPU で続行）: {e}", file=sys.stderr)
    return "cpu"


def _is_oom(err):
    s = str(err)
    return "out of memory" in s.lower() or "CUDA failed" in s


DEFAULT_SPEAKER_MODEL = "speechbrain/spkrec-ecapa-voxceleb"
SPEAKER_MODEL = os.environ.get("SPEAKER_MODEL", DEFAULT_SPEAKER_MODEL)
SPEAKER_VERSION = (
    "speechbrain-ecapa-voxceleb-v1"
    if SPEAKER_MODEL == DEFAULT_SPEAKER_MODEL
    else "speechbrain-custom-v1-" + hashlib.sha256(SPEAKER_MODEL.encode()).hexdigest()[:16]
)
SPEAKER_SAMPLE_RATE = 16_000


def _speaker_encoder():
    """話者埋め込みモデルをCPU（既定）へ読み込む。Whisper用GPUメモリと競合させない。"""
    import torch
    from speechbrain.inference.speaker import EncoderClassifier
    device = os.environ.get("SPEAKER_DEVICE", "cpu").strip().lower()
    if device not in ("cpu", "cuda"):
        device = "cpu"
    cache = os.path.join(os.path.expanduser("~"), ".cache", "aihelper", "speaker-ecapa")
    print(f"話者識別モデルを読み込み中… (device={device})", file=sys.stderr)
    model = EncoderClassifier.from_hparams(
        source=SPEAKER_MODEL,
        savedir=cache,
        run_opts={"device": device},
    )
    return model, torch.device(device)


def _read_audio_range(path, start_sec=0.0, end_sec=None):
    """SoundFileで必要な範囲だけ読み、16kHz mono のtorch.Tensorへ変換する。"""
    import soundfile as sf
    import torch
    import torchaudio.functional as AF
    with sf.SoundFile(path) as audio:
        source_rate = int(audio.samplerate)
        start_frame = max(0, int(start_sec * source_rate))
        audio.seek(min(start_frame, len(audio)))
        frames = -1 if end_sec is None else max(0, int((end_sec - start_sec) * source_rate))
        values = audio.read(frames=frames, dtype="float32", always_2d=True)
    if values.size == 0:
        return torch.empty(0)
    waveform = torch.from_numpy(values.mean(axis=1))
    if source_rate != SPEAKER_SAMPLE_RATE:
        waveform = AF.resample(waveform, source_rate, SPEAKER_SAMPLE_RATE)
    return waveform


def _normalized_embedding(encoder, device, waveforms, lengths=None):
    import torch
    with torch.inference_mode():
        encoded = encoder.encode_batch(waveforms.to(device), wav_lens=lengths)
        encoded = encoded.reshape(encoded.shape[0], -1)
        encoded = torch.nn.functional.normalize(encoded, p=2, dim=1)
    return encoded.cpu()


def enroll_speaker(path):
    """登録音声を3秒窓で埋め込み化し、平均した正規化ベクトルをJSONで返す。"""
    import torch
    waveform = _read_audio_range(path)
    window = SPEAKER_SAMPLE_RATE * 3
    chunks = []
    for start in range(0, max(0, waveform.numel() - window + 1), window):
        chunk = waveform[start:start + window]
        rms = torch.sqrt(torch.mean(chunk * chunk)).item() if chunk.numel() else 0
        if rms >= 0.008:
            chunks.append(chunk)
    if len(chunks) < 2:
        raise RuntimeError("声を十分に検出できませんでした（6秒以上、はっきり話してください）")
    encoder, device = _speaker_encoder()
    batch = torch.stack(chunks)
    embeddings = _normalized_embedding(encoder, device, batch)
    profile = torch.nn.functional.normalize(embeddings.mean(dim=0), p=2, dim=0)
    threshold = float(os.environ.get("SPEAKER_THRESHOLD", "0.35"))
    return {
        "version": SPEAKER_VERSION,
        "embedding": [round(float(v), 8) for v in profile.tolist()],
        "threshold": max(-1.0, min(1.0, threshold)),
    }


def _load_speaker_profile(path):
    import torch
    with open(path, "r", encoding="utf-8") as f:
        profile = json.load(f)
    if profile.get("version") != SPEAKER_VERSION:
        raise RuntimeError("登録声紋のモデルが古いため、スマホから声を再登録してください")
    values = profile.get("embedding")
    if not isinstance(values, list) or not 64 <= len(values) <= 1024:
        raise RuntimeError("登録声紋の形式が不正です")
    embedding = torch.tensor([float(v) for v in values], dtype=torch.float32)
    norm = torch.linalg.vector_norm(embedding).item()
    if not 0.5 <= norm <= 1.5:
        raise RuntimeError("登録声紋が正規化されていません")
    embedding = torch.nn.functional.normalize(embedding, p=2, dim=0)
    return embedding, float(profile.get("threshold", 0.35))


def identify_segments(audio_path, segments, profile_path):
    """Whisperセグメントの音声範囲をECAPAで照合し、各セグメントの話者ラベルを返す。"""
    import torch
    import soundfile as sf
    owner, threshold = _load_speaker_profile(profile_path)
    encoder, device = _speaker_encoder()
    with sf.SoundFile(audio_path) as audio:
        duration = len(audio) / float(audio.samplerate)

    labels = []
    batch_size = max(1, int(os.environ.get("SPEAKER_BATCH", "16")))
    for batch_start in range(0, len(segments), batch_size):
        batch_segments = segments[batch_start:batch_start + batch_size]
        chunks = []
        for seg in batch_segments:
            start = max(0.0, float(seg.start) - 0.15)
            end = min(duration, float(seg.end) + 0.15)
            # 短い相づちも最低1秒の周辺音声を使い、埋め込みを安定させる。
            if end - start < 1.0:
                center = (start + end) / 2
                start = max(0.0, center - 0.5)
                end = min(duration, start + 1.0)
                start = max(0.0, end - 1.0)
            chunks.append(_read_audio_range(audio_path, start, end))

        max_len = max((chunk.numel() for chunk in chunks), default=0)
        if max_len == 0:
            labels.extend([("話者不明", 0.0)] * len(chunks))
            continue
        padded = torch.zeros((len(chunks), max_len), dtype=torch.float32)
        lengths = torch.zeros(len(chunks), dtype=torch.float32)
        for i, chunk in enumerate(chunks):
            padded[i, :chunk.numel()] = chunk
            lengths[i] = chunk.numel() / max_len
        embeddings = _normalized_embedding(encoder, device, padded, lengths.to(device))
        if embeddings.shape[1] != owner.numel():
            raise RuntimeError("登録声紋の次元が現在のモデルと一致しないため、声を再登録してください")
        scores = torch.mv(embeddings, owner)
        for score in scores.tolist():
            labels.append(("オーナー" if score >= threshold else "他人", float(score)))
    return labels


def _run_once(model_name, device, compute, cpu_threads, batch, decode_opts, path, base_opts):
    """1回分の文字起こしを実行する。結果を list 化した時点で実際の推論が走る
    （list() の中で例外＝OOM 等が起きても、途中まで stdout に出してしまわないよう
    ここで全部確定させてから main 側で出力する）。
    """
    from faster_whisper import BatchedInferencePipeline, WhisperModel
    model = WhisperModel(model_name, device=device, compute_type=compute, cpu_threads=cpu_threads)
    opts = dict(base_opts, **decode_opts)
    try:
        if batch > 0:
            pipeline = BatchedInferencePipeline(model=model)
            segments, info = pipeline.transcribe(path, batch_size=batch, **opts)
        else:
            segments, info = model.transcribe(path, **opts)
        return list(segments), info
    finally:
        del model


def main() -> int:
    if len(sys.argv) < 2:
        print("使い方: transcribe.py [--enroll-speaker] <音声ファイル> [--speaker-profile JSON]", file=sys.stderr)
        return 2
    enroll_mode = sys.argv[1] == "--enroll-speaker"
    path_index = 2 if enroll_mode else 1
    if len(sys.argv) <= path_index:
        print("音声ファイルを指定してください", file=sys.stderr)
        return 2
    path = sys.argv[path_index]
    if not os.path.exists(path):
        print(f"ファイルがありません: {path}", file=sys.stderr)
        return 2

    _ensure_cuda_libs()

    if enroll_mode:
        try:
            print(json.dumps(enroll_speaker(path), ensure_ascii=False, separators=(",", ":")))
            return 0
        except Exception as e:
            print(f"話者プロファイル作成失敗: {e}", file=sys.stderr)
            return 1

    speaker_profile_path = None
    if "--speaker-profile" in sys.argv:
        i = sys.argv.index("--speaker-profile")
        if i + 1 >= len(sys.argv):
            print("--speaker-profile にJSONファイルを指定してください", file=sys.stderr)
            return 2
        speaker_profile_path = sys.argv[i + 1]

    device = _pick_device()
    on_gpu = device == "cuda"
    model_name = os.environ.get("WHISPER_MODEL") or ("large-v3" if on_gpu else "large-v3-turbo")
    compute = os.environ.get("WHISPER_COMPUTE") or ("float16" if on_gpu else "int8")
    batch = int(os.environ.get("WHISPER_BATCH") or (16 if on_gpu else 0))
    cpu_threads = int(os.environ.get("WHISPER_CPU_THREADS") or (os.cpu_count() or 4))
    beam_size = int(os.environ.get("WHISPER_BEAM_SIZE") or 10)
    best_of = int(os.environ.get("WHISPER_BEST_OF") or 10)
    patience = float(os.environ.get("WHISPER_PATIENCE") or 2.0)

    # vad_filter で無音区間を飛ばす（ゼミ録音の長い沈黙対策 + 幻覚抑制）。
    base_opts = dict(language="ja", vad_filter=True)
    full_decode = dict(beam_size=beam_size, best_of=best_of, patience=patience)

    # GPU の空き VRAM は他アプリ（デスクトップ環境等）や録音の長さによって変動するため、
    # 精度優先の設定のまま素直に確保できるとは限らない。OOM になったら
    # バッチ→ビーム探索の順に設定を軽くしながら、空いているメモリの範囲で成功するまで再試行する。
    # 最終手段として CPU（large-v3-turbo / int8）にフォールバックし、必ず結果を返す。
    attempts = []
    if on_gpu:
        attempts = [
            (device, model_name, compute, batch, full_decode),
            (device, model_name, compute, max(1, batch // 2), full_decode),
            (device, model_name, compute, 0, full_decode),
            (device, model_name, compute, 0, dict(beam_size=5, best_of=5, patience=1.0)),
        ]
    else:
        attempts = [(device, model_name, compute, batch, full_decode)]

    results = info = None
    for i, (dv, mn, cp, bt, dec) in enumerate(attempts):
        tag = f"（{i+1}/{len(attempts)}回目・空きメモリに合わせて設定を落として再試行）" if i else ""
        print(f"モデル {mn} を読み込み中… (device={dv}, compute={cp}, batch={bt or 'off'}, "
              f"beam={dec['beam_size']}){tag}", file=sys.stderr)
        try:
            results, info = _run_once(mn, dv, cp, cpu_threads, bt, dec, path, base_opts)
            break
        except RuntimeError as e:
            if not (on_gpu and _is_oom(e)):
                raise
            print(f"GPU メモリ不足のため設定を落とします: {e}", file=sys.stderr)
            import gc
            gc.collect()

    if results is None:
        # GPU 側の全段階で OOM だった場合の最終手段。
        print("GPU では空きメモリが足りないため CPU にフォールバックします…", file=sys.stderr)
        cpu_model = os.environ.get("WHISPER_MODEL") or "large-v3-turbo"
        results, info = _run_once(
            cpu_model, "cpu", "int8", cpu_threads, 0,
            dict(beam_size=5, best_of=5, patience=1.0), path, base_opts,
        )

    total = getattr(info, "duration", 0) or 0
    speaker_labels = None
    if speaker_profile_path:
        try:
            speaker_labels = identify_segments(path, results, speaker_profile_path)
        except Exception as e:
            # 声紋登録済みなのに黙ってラベル無しにすると気付きにくいので、このジョブを失敗させて
            # 再試行/再登録へつなげる。
            print(f"話者識別失敗: {e}", file=sys.stderr)
            return 1

    for index, seg in enumerate(results):
        text = seg.text.strip()
        if text:
            if speaker_labels:
                label, score = speaker_labels[index]
                print(f"[{label}] {text}", flush=True)
                print(f"話者 {label} score={score:.3f} {seg.start:.1f}-{seg.end:.1f}s", file=sys.stderr)
            else:
                print(text, flush=True)
        if total:
            print(f"進捗 {seg.end:.0f}/{total:.0f} 秒 ({min(100, seg.end / total * 100):.0f}%)",
                  file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
