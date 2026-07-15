package com.ishilab.transcriber.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.ishilab.transcriber.net.ReminderNotifier

/**
 * 一定間隔でサーバーの未読リマインドを取得し、端末通知を出すためのレシーバ。
 * 録音していないときでも「予定が近づいたら通知」を届けるために使う。
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                ReminderNotifier.poll(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "reminder poll failed: ${e.message}")
            }
            try {
                // 出発・雨・終電アラートも同じ15分周期でチェックする（機能OFFなら何もしない）。
                TravelAssistant.check(context.applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "travel check failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        private const val ACTION = "com.ishilab.transcriber.POLL_REMINDERS"
        private const val REQUEST = 3001

        /** 約15分間隔の定期ポーリングを仕掛ける（アプリ起動時に呼ぶ）。 */
        fun schedule(context: Context) {
            ReminderNotifier.ensureChannel(context)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context)
            // 省電力のため厳密でなくてよい。初回は約1分後、以降15分ごと。
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pi
            )
            Log.i(TAG, "reminder polling scheduled")
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION)
            return PendingIntent.getBroadcast(
                context, REQUEST, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
