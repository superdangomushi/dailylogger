package com.ishilab.transcriber.speaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** オーナー声紋登録専用の12秒録音。呼び出し側は Dispatchers.IO 上で実行する。 */
object OwnerVoiceEnrollmentRecorder {

    const val SAMPLE_RATE = 16_000
    const val ENROLLMENT_SECONDS = 12

    suspend fun record(context: Context, onProgress: (Float) -> Unit): ShortArray {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException("声を登録するにはマイク権限が必要です")
        }

        val sampleRate = SAMPLE_RATE
        val targetSamples = sampleRate * ENROLLMENT_SECONDS
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IllegalStateException("マイクの録音形式を利用できません")
        val bufferSamples = maxOf(minBuffer / 2, sampleRate / 2)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSamples * 2,
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("マイクを初期化できませんでした")
        }

        val out = ShortArray(targetSamples)
        val readBuffer = ShortArray(bufferSamples)
        var written = 0
        try {
            recorder.startRecording()
            while (written < targetSamples) {
                currentCoroutineContext().ensureActive()
                val wanted = minOf(readBuffer.size, targetSamples - written)
                val count = recorder.read(readBuffer, 0, wanted)
                if (count < 0) throw IllegalStateException("録音中にマイクから読み取れませんでした ($count)")
                System.arraycopy(readBuffer, 0, out, written, count)
                written += count
                onProgress(written.toFloat() / targetSamples)
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return out
    }
}
