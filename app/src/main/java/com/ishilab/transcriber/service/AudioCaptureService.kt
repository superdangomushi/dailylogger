package com.ishilab.transcriber.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ishilab.transcriber.MainActivity
import com.ishilab.transcriber.R
import com.ishilab.transcriber.audio.AudioChunker
import com.ishilab.transcriber.model.ModelManager
import com.ishilab.transcriber.model.WhisperModel
import com.ishilab.transcriber.transcribe.TranscriptStore
import com.ishilab.transcriber.transcribe.TranscriptionEngine
import com.ishilab.transcriber.transcribe.WhisperEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * バックグラウンド常時録音＋ローカル文字起こしを行う foreground service。
 *
 * - 録音スレッド: AudioRecord(16kHz/mono/PCM16) を読み取り [AudioChunker] で30秒チャンク化
 * - 文字起こしワーカー: チャンクを順次 whisper で処理し [TranscriptStore] に1時間=1ファイルで追記
 * - 通知から一時停止/再開/終了を制御（一時停止中はマイクを完全解放）
 */
class AudioCaptureService : Service() {

    private lateinit var store: TranscriptStore
    private lateinit var modelManager: ModelManager
    private var engine: TranscriptionEngine? = null

    private lateinit var wakeLock: PowerManager.WakeLock

    private val chunkQueue = LinkedBlockingQueue<FloatArray>(QUEUE_CAPACITY)

    @Volatile private var recordThread: Thread? = null
    @Volatile private var workerThread: Thread? = null
    private val recording = AtomicBoolean(false)   // マイク稼働中か
    private val serviceActive = AtomicBoolean(false) // サービス全体が生存しているか

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = TranscriptStore(filesDir)
        modelManager = ModelManager(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_TAG::lock")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pauseMic()
            ACTION_RESUME -> resumeMic()
            ACTION_STOP -> stopEverything()
            else -> start()
        }
        return START_STICKY
    }

    // ---- ライフサイクル制御 -------------------------------------------------

    private fun start() {
        if (serviceActive.get()) return
        serviceActive.set(true)

        startForegroundCompat()
        if (!wakeLock.isHeld) wakeLock.acquire()

        // モデル読み込み＋ワーカー起動はバックグラウンドで
        workerThread = Thread({ runWorker() }, "transcribe-worker").also { it.start() }
        startRecording()
        pushState { it.copy(active = true, paused = false, error = null) }
    }

    private fun pauseMic() {
        stopRecording()
        pushState { it.copy(paused = true) }
        updateNotification()
    }

    private fun resumeMic() {
        if (!serviceActive.get()) return
        startRecording()
        pushState { it.copy(paused = false) }
        updateNotification()
    }

    private fun stopEverything() {
        serviceActive.set(false)
        stopRecording()
        // ワーカーに終了を通知（poison pill）。キューが満杯でも確実に止めるため interrupt も行う。
        chunkQueue.offer(POISON)
        workerThread?.let { t ->
            t.join(5_000)
            if (t.isAlive) t.interrupt()
        }
        workerThread = null
        engine?.release()
        engine = null
        if (wakeLock.isHeld) wakeLock.release()
        pushState { it.copy(active = false, paused = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (serviceActive.get()) stopEverything()
        super.onDestroy()
    }

    // ---- 録音 ---------------------------------------------------------------

    private fun startRecording() {
        if (recording.get()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pushState { it.copy(error = "録音権限がありません") }
            return
        }
        recording.set(true)
        recordThread = Thread({ recordLoop() }, "audio-record").also { it.start() }
    }

    private fun stopRecording() {
        recording.set(false)
        recordThread?.join(3_000)
        recordThread = null
    }

    private fun recordLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioChunker.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, AudioChunker.SAMPLE_RATE) // 約1秒分以上
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioChunker.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            pushState { it.copy(error = "マイク初期化に失敗: ${e.message}") }
            recording.set(false)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            pushState { it.copy(error = "AudioRecord 初期化失敗") }
            record.release()
            recording.set(false)
            return
        }

        val chunker = AudioChunker()
        val readBuf = ShortArray(bufferSize)
        try {
            record.startRecording()
            Log.i(TAG, "recording started")
            while (recording.get()) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    chunker.append(readBuf, n)
                    while (chunker.hasFullChunk()) {
                        chunker.drainChunk()?.let { enqueue(it) }
                    }
                }
            }
            // 一時停止/停止時の半端な残りも処理に回す
            chunker.flushRemaining()?.let { enqueue(it) }
        } catch (e: Exception) {
            Log.e(TAG, "record loop error", e)
            pushState { it.copy(error = "録音エラー: ${e.message}") }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            Log.i(TAG, "recording stopped (mic released)")
        }
    }

    /** キューが詰まっている(文字起こしが追いつかない)場合は古いチャンクを捨てて実時間を優先。 */
    private fun enqueue(chunk: FloatArray) {
        if (!chunkQueue.offer(chunk)) {
            chunkQueue.poll()
            chunkQueue.offer(chunk)
            pushState { it.copy(dropped = it.dropped + 1) }
            Log.w(TAG, "queue full, dropped oldest chunk")
        } else {
            pushState { it.copy(queueSize = chunkQueue.size) }
        }
    }

    // ---- 文字起こしワーカー -------------------------------------------------

    private fun runWorker() {
        // モデル読み込み
        val model = WhisperModel.entries.firstOrNull { modelManager.isDownloaded(it) }
        if (model == null) {
            pushState { it.copy(error = "モデル未ダウンロード") }
            stopEverything()
            return
        }
        try {
            engine = WhisperEngine(modelManager.modelFile(model).absolutePath).also { it.load() }
            pushState { it.copy(modelName = model.displayName) }
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "model load failed", e)
            pushState { it.copy(error = "モデル読み込み失敗: ${e.message}") }
            stopEverything()
            return
        }

        while (serviceActive.get()) {
            val chunk = try {
                chunkQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (chunk === POISON) break
            pushState { it.copy(queueSize = chunkQueue.size) }

            // 簡易VAD: ほぼ無音のチャンクは文字起こししない
            if (AudioChunker.isSilent(chunk)) {
                continue
            }
            val text = try {
                engine?.transcribe(chunk).orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "transcribe error", e)
                ""
            }
            if (text.isNotBlank()) {
                val now = System.currentTimeMillis()
                store.append(text, now)
                pushState {
                    it.copy(
                        chunksDone = it.chunksDone + 1,
                        lastText = text,
                        currentFile = store.fileFor(now).name
                    )
                }
                updateNotification()
            }
            // 文字起こし済みチャンクの音声データは保持しない（GC に任せ破棄）
        }
        Log.i(TAG, "worker finished")
    }

    // ---- 通知 ---------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文字起こし録音",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "バックグラウンド録音と文字起こしの状態" }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val s = state.value
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when {
            s.paused -> "一時停止中（マイク解放）"
            else -> "録音・文字起こし中"
        }
        val body = buildString {
            s.modelName?.let { append(it).append(" / ") }
            append("処理済 ${s.chunksDone} 件")
            if (s.lastText.isNotBlank()) {
                append("\n直近: ").append(s.lastText.take(40))
            }
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (s.paused) {
            builder.addAction(0, "再開", action(ACTION_RESUME))
        } else {
            builder.addAction(0, "一時停止", action(ACTION_PAUSE))
        }
        builder.addAction(0, "終了", action(ACTION_STOP))
        return builder.build()
    }

    /** 通知アクション → MicControlReceiver 経由でサービスへ。 */
    private fun action(act: String): PendingIntent {
        val intent = Intent(this, MicControlReceiver::class.java).setAction(act)
        return PendingIntent.getBroadcast(
            this, act.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!serviceActive.get()) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private inline fun pushState(block: (ServiceState) -> ServiceState) {
        _state.update(block)
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val WAKE_TAG = "ishilab.transcriber"
        private const val CHANNEL_ID = "transcription"
        private const val NOTIFICATION_ID = 1001
        private const val QUEUE_CAPACITY = 8
        private val POISON = FloatArray(0)

        const val ACTION_START = "com.ishilab.transcriber.START"
        const val ACTION_PAUSE = "com.ishilab.transcriber.PAUSE"
        const val ACTION_RESUME = "com.ishilab.transcriber.RESUME"
        const val ACTION_STOP = "com.ishilab.transcriber.STOP"

        private val _state = MutableStateFlow(ServiceState())
        val state: StateFlow<ServiceState> = _state

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = send(context, ACTION_STOP)

        fun send(context: Context, action: String) {
            val intent = Intent(context, AudioCaptureService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** UI へ公開するサービス状態。 */
data class ServiceState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val modelName: String? = null,
    val chunksDone: Int = 0,
    val dropped: Int = 0,
    val queueSize: Int = 0,
    val lastText: String = "",
    val currentFile: String? = null,
    val error: String? = null,
)
