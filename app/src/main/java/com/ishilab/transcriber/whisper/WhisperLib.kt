package com.ishilab.transcriber.whisper

/**
 * whisper.cpp の JNI ブリッジ。実体は libwhisper-jni.so（src/main/cpp/jni.c）。
 * companion のメソッド名・シグネチャは jni.c の関数名と一致させること。
 */
class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper-jni")
        }

        /** モデルファイルを読み込みコンテキストポインタ(jlong)を返す。0 は失敗。 */
        external fun initContext(modelPath: String): Long

        external fun freeContext(contextPtr: Long)

        /**
         * 16kHz/mono の float PCM(-1.0..1.0) を文字起こし。戻り値 0 が成功。
         * @param lang "ja" など。自動判定したい場合は "auto"。
         */
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            lang: String,
            audioData: FloatArray
        ): Int

        external fun getTextSegmentCount(contextPtr: Long): Int

        external fun getTextSegment(contextPtr: Long, index: Int): String

        external fun getSystemInfo(): String
    }
}
