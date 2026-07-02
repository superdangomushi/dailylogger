package com.ishilab.transcriber.google

import android.content.Context

/**
 * 連携済み Google アカウント（複数可）の保存。
 * defaultEmail は「カレンダーに追加」の登録先に使うアカウント（未設定なら先頭）。
 */
class GoogleAccountStore(context: Context) {

    private val prefs = context.getSharedPreferences("google_accounts", Context.MODE_PRIVATE)

    var emails: List<String>
        get() = (prefs.getString(KEY_EMAILS, "") ?: "").split(',').filter { it.isNotBlank() }
        private set(value) = prefs.edit().putString(KEY_EMAILS, value.joinToString(",")).apply()

    var defaultEmail: String
        get() {
            val d = prefs.getString(KEY_DEFAULT, "") ?: ""
            return if (d in emails) d else emails.firstOrNull() ?: ""
        }
        set(value) = prefs.edit().putString(KEY_DEFAULT, value).apply()

    fun add(email: String) {
        val e = email.trim()
        if (e.isEmpty()) return
        val list = emails
        if (e !in list) emails = list + e
    }

    fun remove(email: String) {
        emails = emails - email
        if (prefs.getString(KEY_DEFAULT, "") == email) {
            prefs.edit().remove(KEY_DEFAULT).apply()
        }
    }

    private companion object {
        const val KEY_EMAILS = "emails"
        const val KEY_DEFAULT = "default_email"
    }
}
