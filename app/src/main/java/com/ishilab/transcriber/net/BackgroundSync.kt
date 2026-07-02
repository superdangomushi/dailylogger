package com.ishilab.transcriber.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ishilab.transcriber.MainActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文字起こしファイルの「サーバー送信（成功するまでリトライ）」と、
 * サーバーからの「リマインドのローカル通知」を担う。
 *
 * 送信ポリシー:
 *  - 完了したファイル（＝現在書き込み中の時刻ファイル以外）だけを送る。
 *  - 送信に成功したファイル名は永続化し、二度送らない（サーバーは冪等だが無駄を省く）。
 *  - 失敗したものは [INTERVAL_MS]（5分）ごと、または [triggerNow] で即時に再送を試みる。
 *
 * ログイン済みのときだけ実働する。通信はブロッキングなので専用スレッドで回す。
 */
class BackgroundSync(private val context: Context) {

    private val accountStore = AccountStore(context)
    private val client = AiHelperClient()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val lock = Object()
    @Volatile private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 現在書き込み中の時刻ファイル名。これは「未完了」として送らない。null なら全て送る。 */
    @Volatile private var currentHourFile: String? = null

    /** 送信すべきファイルが全て送れたときに呼ばれる（終了処理のドレイン判定に使う）。 */
    @Volatile var onAllSent: (() -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        ensureChannel()
        thread = Thread({ loop() }, "AIHelper-sync").also { it.start() }
        Log.i(TAG, "background sync started")
    }

    fun stop() {
        running.set(false)
        synchronized(lock) { lock.notifyAll() }
        thread = null
    }

    /** 書き込み中ファイルを更新。null を渡すと全ファイルが送信対象になる（終了時など）。 */
    fun setCurrentHourFile(name: String?) {
        currentHourFile = name
    }

    /** すぐに送信パスを走らせる（時刻ファイルの切り替わりや終了時に呼ぶ）。 */
    fun triggerNow() {
        synchronized(lock) { lock.notifyAll() }
    }

    private fun loop() {
        while (running.get()) {
            try {
                if (accountStore.loggedIn) {
                    uploadPending()
                    pollReminders()
                    syncCalendar()
                }
                // 送信対象が残っていなければドレイン完了を通知（未ログインも「これ以上送れない」扱い）。
                if (!accountStore.loggedIn || pendingCount() == 0) {
                    onAllSent?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync cycle error: ${e.message}")
            }
            synchronized(lock) {
                if (running.get()) {
                    try {
                        lock.wait(INTERVAL_MS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
        Log.i(TAG, "background sync stopped")
    }

    private fun transcriptFiles(): List<File> {
        val dir = File(context.filesDir, "transcripts")
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.toList() ?: emptyList()
    }

    /** まだ送っていない「完了ファイル」の数。 */
    fun pendingCount(): Int {
        val sent = sentSet()
        val skip = currentHourFile
        return transcriptFiles().count { it.name != skip && it.name !in sent }
    }

    /** 未送信の完了ファイルを送る。成功したら送信済みとして記録。 */
    private fun uploadPending() {
        val sent = sentSet().toMutableSet()
        val skip = currentHourFile
        var uploaded = 0
        for (file in transcriptFiles().sortedBy { it.name }) {
            if (file.name == skip || file.name in sent) continue
            when (client.upload(accountStore.baseUrl, accountStore.email, accountStore.token, file)) {
                is AiHelperClient.Result.Ok -> {
                    sent.add(file.name)
                    uploaded++
                }
                is AiHelperClient.Result.Error -> { /* 次のパスで再送される */ }
            }
        }
        if (uploaded > 0) {
            saveSentSet(sent)
            Log.i(TAG, "uploaded $uploaded file(s)")
        }
    }

    private fun sentSet(): Set<String> =
        prefs.getStringSet(KEY_SENT, emptySet()) ?: emptySet()

    private fun saveSentSet(set: Set<String>) {
        // putStringSet は同じ参照を保持しうるため必ずコピーを渡す。
        prefs.edit().putStringSet(KEY_SENT, HashSet(set)).apply()
    }

    /** サーバーの未読リマインドをローカル通知として表示し、既読化する。 */
    private fun pollReminders() {
        ReminderNotifier.poll(context)
    }

    /** 端末の Google カレンダーから予定を読み取り、サーバーへ同期する。 */
    private fun syncCalendar() {
        try {
            val googleStore = com.ishilab.transcriber.google.GoogleAccountStore(context)
            val emails = googleStore.emails
            if (emails.isEmpty()) return
            val all = mutableListOf<com.ishilab.transcriber.google.CalendarEvent>()
            for (email in emails) {
                try {
                    val token = com.ishilab.transcriber.google.GoogleCalendarClient.accessToken(context, email)
                    val events = com.ishilab.transcriber.google.GoogleCalendarClient.listUpcomingEvents(token).getOrNull()
                    if (events != null) all += events.map { it.copy(accountEmail = email) }
                } catch (e: Exception) {
                    // Ignore auth exceptions in background
                }
            }
            if (all.isNotEmpty()) {
                client.syncCalendar(accountStore.baseUrl, accountStore.email, accountStore.token, all)
            }
        } catch (e: Exception) {
            Log.w(TAG, "calendar sync error: ${e.message}")
        }
    }

    private fun ensureChannel() {
        ReminderNotifier.ensureChannel(context)
    }

    companion object {
        private const val TAG = "BackgroundSync"
        private const val PREFS = "sync_prefs"
        private const val KEY_SENT = "sent_files"
        private const val NOTIF_BASE = 2000
        // 再送の間隔（5分）。triggerNow でこの待機を打ち切って即時実行できる。
        private const val INTERVAL_MS = 5 * 60 * 1000L
    }
}
