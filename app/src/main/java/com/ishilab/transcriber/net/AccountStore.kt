package com.ishilab.transcriber.net

import android.content.Context

/**
 * AIHelper.jp のログイン情報（接続先URL・アカウント・トークン）を端末に保存する。
 * ログインに成功したアカウントだけを保持し、送信時に再利用する。
 */
class AccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("AIHelper", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var email: String
        get() = prefs.getString(KEY_EMAIL, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        private set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    val loggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)

    /** true なら端末の Whisper を使わず、録音音声をサーバーへアップロードして文字起こしする。 */
    var serverTranscribe: Boolean
        get() = prefs.getBoolean(KEY_SERVER_TRANSCRIBE, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVER_TRANSCRIBE, value).apply()

    /** ログイン成功時に呼ぶ。以後の送信で使う認証情報を確定させる。 */
    fun save(baseUrl: String, email: String, token: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_EMAIL, email)
            .putString(KEY_TOKEN, token)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
    }

    private companion object {
        const val DEFAULT_BASE_URL = "https://AIHelper.jp"
        const val KEY_BASE_URL = "base_url"
        const val KEY_EMAIL = "email"
        const val KEY_TOKEN = "token"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_SERVER_TRANSCRIBE = "server_transcribe"
    }
}
