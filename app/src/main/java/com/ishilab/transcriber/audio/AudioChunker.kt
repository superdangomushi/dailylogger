package com.ishilab.transcriber.audio

/**
 * AudioRecord から読み取った PCM16 サンプルを溜め込み、一定長(既定30秒)ごとに
 * whisper 用の float PCM チャンクとして切り出す。
 *
 * whisper は最大30秒窓で処理するため、チャンク長は 30 秒以下にする。
 */
class AudioChunker(
    private val chunkSamples: Int = SAMPLE_RATE * CHUNK_SECONDS
) {
    private var buffer = ShortArray(chunkSamples + SAMPLE_RATE) // 多少の余裕
    private var size = 0

    /** PCM16 サンプルを追加する。 */
    fun append(src: ShortArray, len: Int) {
        ensureCapacity(size + len)
        System.arraycopy(src, 0, buffer, size, len)
        size += len
    }

    /** チャンクが1つ分溜まっていれば true。 */
    fun hasFullChunk(): Boolean = size >= chunkSamples

    /**
     * 先頭から1チャンク分を float(-1.0..1.0) に変換して取り出し、残りは前へ詰める。
     * 溜まっていなければ null。
     */
    fun drainChunk(): FloatArray? {
        if (size < chunkSamples) return null
        val out = toFloat(buffer, chunkSamples)
        val remaining = size - chunkSamples
        System.arraycopy(buffer, chunkSamples, buffer, 0, remaining)
        size = remaining
        return out
    }

    /** 停止時などに、半端な残りをチャンクとして取り出す（最低長未満なら破棄して null）。 */
    fun flushRemaining(minSamples: Int = SAMPLE_RATE): FloatArray? {
        if (size < minSamples) {
            size = 0
            return null
        }
        val out = toFloat(buffer, size)
        size = 0
        return out
    }

    fun reset() {
        size = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
    }

    private fun toFloat(src: ShortArray, len: Int): FloatArray {
        val out = FloatArray(len)
        for (i in 0 until len) {
            out[i] = src[i] / 32768.0f
        }
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SECONDS = 30

        /**
         * 簡易VAD: チャンクの RMS がしきい値未満なら「ほぼ無音」とみなす。
         * 無音チャンクは文字起こしせず破棄し、CPU/バッテリーを節約する。
         */
        fun isSilent(samples: FloatArray, threshold: Float = 0.012f): Boolean {
            if (samples.isEmpty()) return true
            var sumSq = 0.0
            for (s in samples) sumSq += (s * s).toDouble()
            val rms = Math.sqrt(sumSq / samples.size)
            return rms < threshold
        }
    }
}
