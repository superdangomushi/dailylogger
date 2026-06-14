package com.ishilab.transcriber.model

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ggml(whisper) モデルの選択と初回ダウンロードを管理する。
 * ダウンロード後は filesDir/models/ に保存され、以降は完全オフラインで動作する。
 *
 * 日本語を扱うため多言語モデル(.en ではない版)を使う。
 */
enum class WhisperModel(val fileName: String, val displayName: String, val approxMb: Int) {
    TINY("ggml-tiny.bin", "tiny（最軽量・低精度）", 75),
    BASE("ggml-base.bin", "base（標準・推奨）", 142),
    SMALL("ggml-small.bin", "small（高精度・低速）", 466);

    val downloadUrl: String
        get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
}

class ModelManager(context: Context) {

    private val modelsDir: File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(model: WhisperModel): File = File(modelsDir, model.fileName)

    fun isDownloaded(model: WhisperModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() > 1_000_000L // 破損/中断検出の簡易チェック
    }

    /**
     * モデルをダウンロードする（ブロッキング。IO スレッドから呼ぶこと）。
     * @param onProgress 0.0..1.0（Content-Length 不明時は -1 を渡す）
     */
    fun download(model: WhisperModel, onProgress: (Float) -> Unit) {
        val target = modelFile(model)
        val tmp = File(target.parentFile, "${target.name}.part")
        val conn = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            if (conn.responseCode !in 200..299) {
                error("ダウンロード失敗 HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var downloaded = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        downloaded += read
                        onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                    }
                }
            }
            check(tmp.renameTo(target)) { "モデルの保存に失敗しました" }
            Log.i(TAG, "model downloaded: ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "ModelManager"
        val DEFAULT = WhisperModel.BASE
    }
}
