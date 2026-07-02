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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ishilab.transcriber.MainActivity
import com.ishilab.transcriber.R
import com.ishilab.transcriber.audio.AudioChunker
import com.ishilab.transcriber.audio.PcmSegment
import com.ishilab.transcriber.audio.PcmSegmentWriter
import com.ishilab.transcriber.net.BackgroundSync
import com.ishilab.transcriber.model.ModelManager
import com.ishilab.transcriber.model.WhisperModel
import com.ishilab.transcriber.transcribe.TranscriptStore
import com.ishilab.transcriber.transcribe.TranscriptionEngine
import com.ishilab.transcriber.transcribe.WhisperEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * バックグラウンド録音＋ローカル文字起こしを行う foreground service。
 *
 * 文字起こしは「録音しながら」ではなく、区切りが確定した音声を**まとめて**行う:
 * - 録音スレッド: AudioRecord(16kHz/mono/PCM16) を読み取り、PCM を **区間ファイル**へ書き出す。
 * - 実時刻で1時間ごとに区間を締め、直前1時間ぶんの音声をワーカーがまとめて文字起こしする。
 * - 終了ボタンが押された時点でも、その区間をまとめて文字起こしする（これらのどちらか早い方）。
 * - 文字起こしが済んだテキストは [TranscriptStore] に保存し、即サーバー送信（失敗時は再送）。
 */
class AudioCaptureService : Service() {

    private lateinit var store: TranscriptStore
    private lateinit var modelManager: ModelManager
    private var engine: TranscriptionEngine? = null
    private lateinit var accountStore: com.ishilab.transcriber.net.AccountStore
    private val aiHelper = com.ishilab.transcriber.net.AiHelperClient()
    // 送信に失敗した音声区間の退避先（BackgroundSync が接続復帰時にまとめて再送する）。
    private lateinit var audioOutboxDir: File

    private lateinit var wakeLock: PowerManager.WakeLock
    @Volatile private var backgroundSync: BackgroundSync? = null
    private val shutdownGuard = AtomicBoolean(false)

    // 文字起こし待ちの「確定した音声区間」。録音とは非同期にワーカーが処理する。
    private val segmentQueue = LinkedBlockingQueue<Segment>()
    private lateinit var segmentsDir: File
    @Volatile private var segWriter: PcmSegmentWriter? = null
    @Volatile private var segStartMillis: Long = 0L
    @Volatile private var segHourKey: String = ""

    @Volatile private var recordThread: Thread? = null
    @Volatile private var workerThread: Thread? = null
    private val recording = AtomicBoolean(false)   // マイク稼働中か
    private val serviceActive = AtomicBoolean(false) // サービス全体が生存しているか

    /** 文字起こし待ち/実行対象の音声区間。 */
    private data class Segment(val file: File, val startMillis: Long, val label: String)

    // 開始/一時停止/再開/終了の各処理はブロッキング(スレッドjoin等)を含むため、
    // メインスレッドをふさいで ANR を起こさないよう専用スレッドで直列実行する。
    private val control: ExecutorService = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = TranscriptStore(filesDir)
        modelManager = ModelManager(this)
        accountStore = com.ishilab.transcriber.net.AccountStore(this)
        segmentsDir = File(filesDir, "segments").apply { mkdirs() }
        audioOutboxDir = File(filesDir, "audio-outbox").apply { mkdirs() }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$WAKE_TAG::lock")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() の「5秒以内に startForeground()」制約を満たすため、
        // どのアクションでもまずフォアグラウンド化だけは同期的に行う（通知構築のみで軽量）。
        startForegroundCompat()

        val action = intent?.action ?: ACTION_START
        // 実処理はブロッキングを含むので専用スレッドへ。メインスレッドは即座に返す。
        control.execute {
            when (action) {
                ACTION_START -> start()
                ACTION_PAUSE -> pauseMic()
                ACTION_RESUME -> resumeMic()
                ACTION_STOP -> stopEverything()
                else -> start()
            }
        }
        return START_STICKY
    }

    // ---- ライフサイクル制御 -------------------------------------------------

    private fun start() {
        if (serviceActive.get()) return
        serviceActive.set(true)

        if (!wakeLock.isHeld) wakeLock.acquire()

        // 新しいセッション開始。録音時間の計測をリセット。
        pushState {
            it.copy(
                active = true, paused = false, error = null,
                accumulatedRecordMs = 0L, recordingStartedElapsed = 0L
            )
        }
        // モデル読み込み＋ワーカー起動はバックグラウンドで
        workerThread = Thread({ runWorker() }, "transcribe-worker").also { it.start() }
        startRecording()
        // 定期アップロード＋リマインド通知（ログイン済みのときだけ実働）。
        backgroundSync = BackgroundSync(applicationContext).also { it.start() }
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
        val wasActive = serviceActive.getAndSet(false)
        recording.set(false)
        if (wasActive) {
            accrueRecordingTime()
            recordThread?.join(3_000)
            recordThread = null
            // 終了時: 録音済みの残り区間を確定させ、文字起こし対象に積む。
            finalizeSegment()
            // ワーカーに終了を通知。積んである区間は POISON より前なので必ず処理される。
            segmentQueue.offer(POISON)
            // 最後の区間の文字起こし＋保存が終わるまで待つ（長時間になり得るので余裕を持つ）。
            val worker = workerThread
            worker?.join(WORKER_JOIN_MS)
            val workerStuck = worker?.isAlive == true
            if (workerStuck) worker?.interrupt()
            workerThread = null
            // 文字起こし(ネイティブ)実行中に release() すると @Synchronized のロック待ちで
            // 永遠にブロックする。ワーカーが確実に終わっているときだけ解放する。
            if (!workerStuck) {
                engine?.release()
                engine = null
            } else {
                Log.w(TAG, "transcription still running; skip release to avoid deadlock")
            }
        }
        pushState { it.copy(active = false, paused = false, transcribing = false, recordingStartedElapsed = 0L) }
        // 録音・文字起こしは止めるが、未送信ファイル/音声の送信が終わるまで常駐を維持する。
        startDraining()
    }

    /** 終了後、未送信の文字起こしファイル/音声が全て送れるまで送信を続け、完了したら自分を止める。 */
    private fun startDraining() {
        val sync = backgroundSync
        if (sync == null) {
            finishShutdown()
            return
        }
        sync.setCurrentHourFile(null)      // 現在の時刻ファイルも送信対象に含める
        sync.onAllSent = { finishShutdown() }
        pushState { it.copy(draining = true) }
        updateNotification()               // 「送信待ち…」表示に更新
        sync.triggerNow()                  // すぐに送信パスを走らせる
    }

    /** 送信完了（または送信不能）時の最終後始末。多重実行を防ぐ。 */
    private fun finishShutdown() {
        if (!shutdownGuard.compareAndSet(false, true)) return
        backgroundSync?.stop()
        backgroundSync = null
        if (wakeLock.isHeld) wakeLock.release()
        pushState { it.copy(draining = false) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 録音中に経過した時間を積算し、計測を停止状態にする。 */
    private fun accrueRecordingTime() {
        pushState {
            if (it.recordingStartedElapsed > 0L) {
                it.copy(
                    accumulatedRecordMs = it.accumulatedRecordMs +
                        (SystemClock.elapsedRealtime() - it.recordingStartedElapsed),
                    recordingStartedElapsed = 0L
                )
            } else it
        }
    }

    override fun onDestroy() {
        // 外部要因で破棄された場合の後始末。メインスレッドをブロックしないよう control で。
        if (serviceActive.getAndSet(false)) {
            recording.set(false)
            segmentQueue.offer(POISON)
            control.execute {
                recordThread?.join(2_000); recordThread = null
                workerThread?.join(2_000); workerThread = null
                engine?.release(); engine = null
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
        backgroundSync?.stop()
        backgroundSync = null
        control.shutdown()
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
        pushState { it.copy(recordingStartedElapsed = SystemClock.elapsedRealtime()) }
        recordThread = Thread({ recordLoop() }, "audio-record").also { it.start() }
    }

    private fun stopRecording() {
        recording.set(false)
        accrueRecordingTime()
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

        val readBuf = ShortArray(bufferSize)
        try {
            record.startRecording()
            Log.i(TAG, "recording started")
            ensureSegmentWriter()
            while (recording.get()) {
                val n = record.read(readBuf, 0, readBuf.size)
                if (n > 0) {
                    segWriter?.append(readBuf, n)
                    // 実時刻の「時」が変わったら、直前1時間ぶんを確定して文字起こしへ回す。
                    val key = hourKey(System.currentTimeMillis())
                    if (key != segHourKey) rotateSegment()
                }
            }
            // 一時停止/停止では区間を閉じない（stop 側で finalizeSegment する）。
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

    /** 現在の区間ライタが無ければ作る。 */
    private fun ensureSegmentWriter() {
        if (segWriter != null) return
        val now = System.currentTimeMillis()
        segStartMillis = now
        segHourKey = hourKey(now)
        val f = File(segmentsDir, "seg-${now}.pcm")
        segWriter = PcmSegmentWriter(f)
    }

    /** 実時刻の時が変わったとき: 現区間を閉じてキューに積み、新しい区間を開始する。 */
    private fun rotateSegment() {
        finalizeSegment()
        ensureSegmentWriter()
    }

    /** 現区間を閉じ、十分な長さがあれば文字起こしキューへ積む。 */
    private fun finalizeSegment() {
        val w = segWriter ?: return
        segWriter = null
        w.close()
        if (w.samples >= MIN_SEGMENT_SAMPLES) {
            val seg = Segment(w.file, segStartMillis, hourLabel(segStartMillis))
            segmentQueue.offer(seg)
            pushState { it.copy(queueSize = segmentQueue.size) }
        } else {
            w.file.delete() // 短すぎる区間は破棄
        }
    }

    /** 実時刻の「時」を表すキー（TranscriptStore のファイル名と揃える）。 */
    private fun hourKey(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd_HH", Locale.JAPAN).format(Date(millis))

    /** 「7/2 14時台」のような表示用ラベル。 */
    private fun hourLabel(millis: Long): String =
        SimpleDateFormat("M/d H時台", Locale.JAPAN).format(Date(millis))

    // ---- 文字起こしワーカー -------------------------------------------------

    private fun runWorker() {
        // サーバー文字起こしモード: 端末では Whisper を回さず、音声をアップロードするだけ。
        val serverMode = accountStore.serverTranscribe && accountStore.loggedIn
        if (serverMode) {
            pushState { it.copy(modelName = "サーバー処理（音声アップロード）") }
            updateNotification()
            backgroundSync?.triggerNow() // 前回送れなかった区間があれば同期ループで再送する
        } else {
            // モデル読み込み（利用者が選択したモデルを優先。未DLならDL済みの先頭）
            val model = modelManager.activeModel()
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
        }

        // POISON が来るまで（＝終了指示まで）は、積まれた区間を全て処理し切る。
        while (true) {
            val seg = try {
                segmentQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            if (seg === POISON) break
            if (serverMode) uploadSegment(seg) else transcribeSegment(seg)
            pushState { it.copy(queueSize = segmentQueue.size) }
        }
        Log.i(TAG, "worker finished")
    }

    /**
     * サーバー文字起こしモード: 区間 PCM を WAV としてアップロードする。
     * 失敗したら outbox に退避し、次の機会（次区間成功時・次回起動時）に再送する。
     */
    private fun uploadSegment(seg: Segment) {
        pushState { it.copy(transcribing = true, transcribeLabel = "${seg.label} を送信", transcribeProgress = 0f) }
        updateNotification()
        val uploadName = hourKey(seg.startMillis) + ".wav"
        var ok = false
        try {
            for (attempt in 1..3) {
                val r = aiHelper.uploadAudioPcm(
                    accountStore.baseUrl, accountStore.email, accountStore.token,
                    seg.file, uploadName, AudioChunker.SAMPLE_RATE
                )
                if (r.isSuccess) { ok = true; break }
                Log.w(TAG, "audio upload failed (try $attempt): ${r.exceptionOrNull()?.message}")
                Thread.sleep(5_000L * attempt)
            }
        } catch (_: InterruptedException) {
            // 終了要求。区間は outbox に退避して次回送る。
        } finally {
            if (ok) {
                seg.file.delete()
                pushState {
                    it.copy(
                        transcribing = false, transcribeLabel = null,
                        chunksDone = it.chunksDone + 1,
                        lastText = "${seg.label} をサーバーへ送信しました（サーバーで文字起こし中）",
                    )
                }
                backgroundSync?.triggerNow() // 通信が生きているうちに滞留分も送る
            } else {
                // ファイル名に開始時刻が入っているので、そのまま outbox へ移して後で再送する。
                moveToAudioOutbox(seg.file)
                backgroundSync?.triggerNow()
                pushState {
                    it.copy(
                        transcribing = false, transcribeLabel = null,
                        error = "音声のアップロードに失敗しました（次回自動再送します）",
                    )
                }
            }
            updateNotification()
        }
    }

    /** 区間ファイルを outbox に残す。rename が使えない場合だけコピーで退避する。 */
    private fun moveToAudioOutbox(file: File) {
        val moved = File(audioOutboxDir, file.name)
        if (file.renameTo(moved)) return
        try {
            file.copyTo(moved, overwrite = true)
            file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "failed to move audio segment to outbox: ${e.message}")
        }
    }

    /** 1区間(最大1時間)を30秒窓で順に文字起こしし、テキストを保存して送信をトリガする。 */
    private fun transcribeSegment(seg: Segment) {
        val windowSamples = AudioChunker.SAMPLE_RATE * AudioChunker.CHUNK_SECONDS
        val totalWindows = maxOf(1, ((seg.file.length() / 2) / windowSamples + 1).toInt())
        val sb = StringBuilder()
        var index = 0
        pushState { it.copy(transcribing = true, transcribeLabel = seg.label, transcribeProgress = 0f) }
        updateNotification()
        try {
            PcmSegment.forEachWindow(seg.file, windowSamples) { window ->
                if (!AudioChunker.isSilent(window)) {
                    val part = try {
                        engine?.transcribe(window).orEmpty()
                    } catch (e: Exception) {
                        Log.e(TAG, "transcribe error", e); ""
                    }
                    if (part.isNotBlank()) sb.append(part).append(' ')
                }
                index++
                pushState { it.copy(transcribeProgress = (index.toFloat() / totalWindows).coerceIn(0f, 1f)) }
                // サービス終了要求が来ていても、終了区間は処理し切りたいので中断しない。
                true
            }
        } finally {
            seg.file.delete() // 音声データは保持しない
            pushState { it.copy(transcribing = false, transcribeProgress = 0f, transcribeLabel = null) }
        }

        val text = sb.toString().trim()
        if (text.isNotBlank()) {
            store.append(text, seg.startMillis)
            val fileName = store.fileFor(seg.startMillis).name
            pushState {
                it.copy(chunksDone = it.chunksDone + 1, lastText = text, currentFile = fileName)
            }
            updateNotification()
            backgroundSync?.triggerNow() // 文字起こしできたら即送信（失敗時はリトライ）
        }
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
            s.draining -> "送信待ち（未送信を送信中）"
            s.transcribing -> "音声を文字起こし中"
            s.paused -> "一時停止中（マイク解放）"
            else -> "録音中（1時間ごと/終了時にまとめて文字起こし）"
        }
        val body = buildString {
            if (s.transcribing) {
                val pct = (s.transcribeProgress * 100).toInt()
                append(s.transcribeLabel ?: "音声").append(" を処理中 ").append(pct).append('%')
            } else {
                append("処理済 ${s.chunksDone} 区間")
                if (s.queueSize > 0) append(" / 待機 ${s.queueSize}")
            }
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

        if (!s.draining) {
            if (s.paused) {
                builder.addAction(0, "再開", action(ACTION_RESUME))
            } else {
                builder.addAction(0, "一時停止", action(ACTION_PAUSE))
            }
            builder.addAction(0, "終了", action(ACTION_STOP))
        }
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
        if (!serviceActive.get() && !state.value.draining) return
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
        // これ未満の長さの区間は文字起こししない（1秒）。
        private const val MIN_SEGMENT_SAMPLES = 16_000L
        // 終了時、最後の区間の文字起こし完了を待つ上限。
        private const val WORKER_JOIN_MS = 30 * 60 * 1000L
        private val POISON = Segment(File(""), 0L, "")

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
    val transcribing: Boolean = false,
    /** 処理中の音声区間の表示ラベル（例: 「7/2 14時台」）。 */
    val transcribeLabel: String? = null,
    /** 現在処理中区間の進捗 0.0..1.0。 */
    val transcribeProgress: Float = 0f,
    val draining: Boolean = false,
    val overloaded: Boolean = false,
    val error: String? = null,
    /** 現在のマイク稼働区間の開始時刻(elapsedRealtime)。0 のとき計測停止中。 */
    val recordingStartedElapsed: Long = 0L,
    /** 過去の稼働区間の積算録音時間(ms)。一時停止をまたいだ合計に使う。 */
    val accumulatedRecordMs: Long = 0L,
)
