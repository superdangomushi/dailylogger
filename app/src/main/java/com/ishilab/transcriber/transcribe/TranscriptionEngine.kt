package com.ishilab.transcriber.transcribe

/**
 * 文字起こしエンジンの抽象。現在は [WhisperEngine] のみだが、
 * 将来 Vosk 等へ差し替えられるよう interface 化している。
 */
interface TranscriptionEngine {
    /** モデルを読み込む。失敗時は例外。 */
    fun load()

    /**
     * 16kHz/mono・float PCM(-1.0..1.0) を文字起こししてテキストを返す。
     * 無音や認識不能の場合は空文字を返すことがある。
     */
    fun transcribe(samples: FloatArray): String

    /** リソース解放。 */
    fun release()

    val isLoaded: Boolean
}
