package com.ishilab.transcriber.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 録音した PCM16(16kHz/mono) を一旦ディスクへ書き出しておくためのライタ。
 * 1時間分でも RAM を圧迫しないよう、逐次ファイルへ追記する。
 */
class PcmSegmentWriter(val file: File) {
    private val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
    var samples: Long = 0L
        private set
    private var scratch = ByteArray(0)

    /** PCM16 サンプルをリトルエンディアンのバイト列として書き出す。 */
    fun append(src: ShortArray, len: Int) {
        if (scratch.size < len * 2) scratch = ByteArray(len * 2)
        var j = 0
        for (i in 0 until len) {
            val s = src[i].toInt()
            scratch[j++] = (s and 0xFF).toByte()
            scratch[j++] = ((s shr 8) and 0xFF).toByte()
        }
        out.write(scratch, 0, len * 2)
        samples += len
    }

    fun close() {
        try { out.flush() } finally { out.close() }
    }
}

object PcmSegment {
    /**
     * PCM ファイルを windowSamples ごとの float(-1.0..1.0) 配列にして順に渡す。
     * onWindow が false を返すと途中で打ち切る。メモリは1窓ぶんしか使わない。
     */
    fun forEachWindow(file: File, windowSamples: Int, onWindow: (FloatArray) -> Boolean) {
        BufferedInputStream(FileInputStream(file), 1 shl 16).use { ins ->
            val byteBuf = ByteArray(windowSamples * 2)
            while (true) {
                var read = 0
                while (read < byteBuf.size) {
                    val n = ins.read(byteBuf, read, byteBuf.size - read)
                    if (n < 0) break
                    read += n
                }
                if (read <= 0) break
                val count = read / 2
                val floats = FloatArray(count)
                var bi = 0
                for (i in 0 until count) {
                    val lo = byteBuf[bi].toInt() and 0xFF
                    val hi = byteBuf[bi + 1].toInt() // 符号拡張される（上位バイト）
                    bi += 2
                    floats[i] = ((hi shl 8) or lo) / 32768.0f
                }
                if (!onWindow(floats)) break
                if (read < byteBuf.size) break // 最後の半端窓で終了
            }
        }
    }
}
