package com.ishilab.transcriber

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ishilab.transcriber.google.GoogleCalendarClient
import com.ishilab.transcriber.model.WhisperModel
import com.ishilab.transcriber.net.AiHelperClient
import com.ishilab.transcriber.service.AudioCaptureService
import com.ishilab.transcriber.service.DailyDigestScheduler
import com.ishilab.transcriber.service.DigestTimeStore
import com.ishilab.transcriber.service.NotificationPrefs
import com.ishilab.transcriber.service.ServiceState
import com.ishilab.transcriber.service.TravelAssistant
import com.ishilab.transcriber.service.TravelPrefs
import com.ishilab.transcriber.ui.ChatMessage
import com.ishilab.transcriber.ui.MainViewModel
import com.ishilab.transcriber.ui.TranscriptItem
import com.ishilab.transcriber.ui.UiState
import java.io.File

// 録音タブのUI（状態・操作・モデル管理・文字起こしモード）。
// MainActivity.kt から分割。

/** 録音・文字起こし関連（状態 / 操作 / 受信ファイル）をまとめたタブ。 */
@Composable
internal fun RecordingTab(
    ui: UiState,
    service: ServiceState,
    onDownload: (WhisperModel) -> Unit,
    onSelectModel: (WhisperModel) -> Unit,
    onSetServerTranscribe: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // タブ内は単一の LazyColumn にして全体をスクロール可能に保つ。
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StatusCard(service) }

        item { TranscribeModeCard(ui, onSetServerTranscribe) }

        if (!ui.serverTranscribe) {
            item { ModelCard(ui, onDownload, onSelectModel) }
        }

        if (ui.anyModelReady || ui.serverTranscribe) {
            item { ControlRow(service, onStart, onStop) }
        }

        service.error?.let { err ->
            item { Text("エラー: $err", color = MaterialTheme.colorScheme.error) }
        }

        ui.sendMessage?.let { msg ->
            item { Text(msg, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable
internal fun StatusCard(service: ServiceState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val status = when {
                service.draining -> "送信待ち（未送信を送信中）"
                service.transcribing -> "音声を文字起こし中"
                !service.active -> "停止中"
                service.paused -> "一時停止中（マイク解放中）"
                else -> "録音中"
            }
            Text("状態: $status", style = MaterialTheme.typography.titleMedium)
            if (service.active && !service.transcribing) {
                val elapsedMs = rememberRecordingElapsed(service)
                Text("録音時間: ${formatDuration(elapsedMs)}")
                Text(
                    "※ 文字起こしは1時間ごと、または終了時にまとめて実行します。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            // 現在どの区間を処理しているかと進捗。
            if (service.transcribing) {
                val pct = (service.transcribeProgress * 100).toInt()
                Text("処理中の音声: ${service.transcribeLabel ?: "-"}")
                LinearProgressIndicator(
                    progress = { service.transcribeProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("$pct%", style = MaterialTheme.typography.bodySmall)
            }
            service.modelName?.let { Text("モデル: $it") }
            Text("処理済: ${service.chunksDone} 区間  待機: ${service.queueSize} 区間")
            service.currentFile?.let { Text("最新の出力: $it") }
            if (service.lastText.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "直近: ${service.lastText}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
/**
 * 録音の合計継続時間(ms)を返し、録音中は毎秒再計算してカウントアップさせる。
 * 一時停止中は積算値で止まる。
 */
@Composable
internal fun rememberRecordingElapsed(service: ServiceState): Long {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(service.active, service.recordingStartedElapsed) {
        while (service.active) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val running = if (service.recordingStartedElapsed > 0L) {
        (now - service.recordingStartedElapsed).coerceAtLeast(0L)
    } else 0L
    return service.accumulatedRecordMs + running
}
internal fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
@Composable
internal fun ControlRow(service: ServiceState, onStart: () -> Unit, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStart, enabled = !service.active) { Text("録音開始") }
        OutlinedButton(onClick = onStop, enabled = service.active) { Text("終了") }
    }
    Text(
        "※ 一時停止/再開は通知バーのボタンから行えます。",
        style = MaterialTheme.typography.bodySmall
    )
}
/**
 * 文字起こし方法の選択カード。
 * 端末処理(Whisper)は遅い端末だと時間がかかるため、音声をサーバーへアップロードして
 * サーバー側で文字起こしするモードを選べる（AIHelper ログインが必要）。
 */
@Composable
internal fun TranscribeModeCard(ui: UiState, onSetServerTranscribe: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("文字起こしの方法", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = !ui.serverTranscribe, onClick = { onSetServerTranscribe(false) })
                Column {
                    Text("端末で処理（オフライン）", style = MaterialTheme.typography.bodyMedium)
                    Text("Whisper モデルで端末内処理。通信不要だが時間がかかる。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = ui.serverTranscribe,
                    onClick = { onSetServerTranscribe(true) },
                    enabled = ui.account.loggedIn
                )
                Column {
                    Text("サーバーで処理（音声をアップロード）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (ui.account.loggedIn)
                            "録音区間の音声をサーバーへ送り、サーバー側で文字起こし。処理状況はダッシュボードで確認できます。"
                        else "利用するには先に「AI」タブで AIHelper にログインしてください。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "※ 切り替えは次回の録音開始から反映されます。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
/**
 * 文字起こしモデルのカード。ダウンロード済みモデルはラジオで選び直せ、
 * 未ダウンロードのモデルはこの場でダウンロードできる。
 */
@Composable
internal fun ModelCard(
    ui: UiState,
    onDownload: (WhisperModel) -> Unit,
    onSelectModel: (WhisperModel) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("文字起こしモデル", style = MaterialTheme.typography.titleMedium)
            if (!ui.anyModelReady) {
                Text(
                    "初回はモデルのダウンロードが必要です。DL後はオフラインで動作。日本語は base 以上を推奨。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            WhisperModel.entries.forEach { model ->
                val downloaded = model in ui.downloadedModels
                val selected = ui.selectedModel == model
                val isDownloading = ui.downloading == model
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text("約${model.approxMb}MB", style = MaterialTheme.typography.bodySmall)
                    }
                    when {
                        isDownloading -> CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            strokeWidth = 2.dp
                        )
                        downloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selected,
                                onClick = { onSelectModel(model) }
                            )
                            Text(
                                if (selected) "使用中" else "使用",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        else -> Button(
                            onClick = { onDownload(model) },
                            enabled = ui.downloading == null
                        ) { Text("ダウンロード") }
                    }
                }
                if (isDownloading) {
                    if (ui.downloadProgress >= 0f) {
                        LinearProgressIndicator(
                            progress = { ui.downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            ui.downloadError?.let {
                Text("ダウンロード失敗: $it", color = MaterialTheme.colorScheme.error)
            }
            Text(
                "※ 録音中に変更した場合は次回の録音開始から反映されます。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
