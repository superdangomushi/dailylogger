package com.ishilab.transcriber.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知のアクションボタン（一時停止/再開/終了）を受け取り、
 * すでに起動中の [AudioCaptureService] へコマンドを転送する。
 */
class MicControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        when (action) {
            AudioCaptureService.ACTION_PAUSE,
            AudioCaptureService.ACTION_RESUME,
            AudioCaptureService.ACTION_STOP ->
                AudioCaptureService.send(context, action)
        }
    }
}
