package com.ishilab.transcriber.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ishilab.transcriber.MainActivity

/**
 * サーバーの未読リマインド（締切が近い課題・予定）を取得して端末のローカル通知を出す。
 * 録音サービスと定期アラームの双方から使えるよう、単独オブジェクトに切り出している。
 */
object ReminderNotifier {

    private const val TAG = "ReminderNotifier"
    const val CHANNEL_ID = "reminders"
    private const val NOTIF_BASE = 2000

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "締切・予定リマインド",
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = "課題や予定の締切が近いときの通知" }
        nm.createNotificationChannel(channel)
    }

    /** 未読リマインドを取得し、ローカル通知として表示して既読化する。ブロッキング。 */
    fun poll(context: Context) {
        val store = AccountStore(context)
        if (!store.loggedIn) return
        val client = AiHelperClient()
        val reminders = client.fetchReminders(store.baseUrl, store.email, store.token)
        if (reminders.isEmpty()) return
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val acked = ArrayList<Long>(reminders.size)
        for (r in reminders) {
            nm.notify(NOTIF_BASE + (r.id % 100000).toInt(), build(context, r.message))
            acked.add(r.id)
        }
        client.ackReminders(store.baseUrl, store.email, store.token, acked)
        Log.i(TAG, "showed ${reminders.size} reminder notification(s)")
    }

    private fun build(context: Context, message: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = message.lineSequence().firstOrNull()?.take(40) ?: "リマインド"
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }
}
