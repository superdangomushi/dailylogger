package com.ishilab.transcriber.net

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * moneybot.jp とやり取りするためのクライアント。
 *
 * 追加ライブラリを増やさないため HttpURLConnection と org.json のみで実装する。
 * 通信はブロッキングなので必ずワーカースレッド（Dispatchers.IO）から呼ぶこと。
 */
class MoneybotClient {

    sealed interface Result {
        data class Ok(val message: String) : Result
        data class Error(val message: String) : Result
    }

    /** ログイン照合。アプリで入力したアカウント情報＋トークンがサーバーと一致するか確認する。 */
    fun login(baseUrl: String, email: String, token: String): Result {
        val url = endpoint(baseUrl, "/api/login")
        val body = JSONObject().put("email", email).put("token", token).toString()
        return runCatching {
            val conn = openPost(url, "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResult(conn, onOk = "ログインしました")
        }.getOrElse { Result.Error(it.message ?: "通信に失敗しました") }
    }

    /** 文字起こしファイルを送信する。サーバー側で email＋トークンの一致を確認してから保存される。 */
    fun upload(baseUrl: String, email: String, token: String, file: File): Result {
        if (!file.exists()) return Result.Error("ファイルが見つかりません")
        val url = endpoint(baseUrl, "/api/upload")
        return runCatching {
            val conn = openPost(url, "text/plain; charset=utf-8").apply {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Account-Email", email)
                setRequestProperty("X-Filename", file.name)
            }
            conn.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            readResult(conn, onOk = "${file.name} を送信しました")
        }.getOrElse { Result.Error(it.message ?: "送信に失敗しました") }
    }

    private fun openPost(url: URL, contentType: String): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
        }

    private fun readResult(conn: HttpURLConnection, onOk: String): Result {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code in 200..299) return Result.Ok(onOk)
        val serverMsg = runCatching { JSONObject(text).optString("error") }.getOrNull()
        return Result.Error(
            serverMsg?.takeIf { it.isNotBlank() } ?: "サーバーエラー (HTTP $code)"
        )
    }

    private fun endpoint(baseUrl: String, path: String): URL =
        URL(baseUrl.trimEnd('/') + path)
}
