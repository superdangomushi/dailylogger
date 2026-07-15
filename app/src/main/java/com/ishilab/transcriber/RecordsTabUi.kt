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

// 記録タブのUI（端末/サーバーの文字起こし一覧と詳細）。
// MainActivity.kt から分割。

/** 文字起こし記録を「日付 → 時刻 → 本文」の階層で辿るタブ。 */
@Composable
internal fun RecordsTab(
    ui: UiState,
    onRefresh: () -> Unit,
    onSend: (TranscriptItem) -> Unit,
    onLoadServerTranscripts: () -> Unit,
    onLoadServerTranscript: (Long) -> Unit,
) {
    var source by rememberSaveable { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = source) {
            Tab(selected = source == 0, onClick = { source = 0 }, text = { Text("端末") })
            Tab(selected = source == 1, onClick = { source = 1 }, text = { Text("サーバー") })
        }
        when (source) {
            0 -> LocalRecordsList(ui, onRefresh, onSend)
            else -> ServerRecordsList(ui, onLoadServerTranscripts, onLoadServerTranscript)
        }
    }
}
@Composable
internal fun LocalRecordsList(
    ui: UiState,
    onRefresh: () -> Unit,
    onSend: (TranscriptItem) -> Unit,
) {
    val context = LocalContext.current
    var openDate by rememberSaveable { mutableStateOf<String?>(null) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }

    // ファイル名 "yyyy-MM-dd_HH.txt" を日付・時でグループ化。
    fun dateOf(name: String) = if (name.length >= 10) name.take(10) else name
    fun hourOf(name: String) = if (name.length >= 13) name.substring(11, 13) else "--"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ヘッダー（パンくず＋更新）
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val crumb = when {
                    openFile != null -> "記録 › $openDate › ${hourOf(openFile!!)}時台"
                    openDate != null -> "記録 › $openDate"
                    else -> "記録（日付一覧）"
                }
                Text(crumb, style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onRefresh) { Text("更新") }
            }
        }

        if (ui.transcripts.isEmpty()) {
            item { Text("まだ記録がありません。", style = MaterialTheme.typography.bodySmall) }
            return@LazyColumn
        }

        when {
            // ---- 第3階層: 本文表示 ----
            openFile != null -> {
                val item = ui.transcripts.firstOrNull { it.name == openFile }
                item { TextButton(onClick = { openFile = null }) { Text("← ${openDate} の時刻一覧へ") } }
                if (item == null) {
                    item { Text("ファイルが見つかりません。", style = MaterialTheme.typography.bodySmall) }
                } else {
                    item { TranscriptDetail(item, ui, onSend, context) }
                }
            }
            // ---- 第2階層: 選択した日付の時刻一覧 ----
            openDate != null -> {
                item { TextButton(onClick = { openDate = null }) { Text("← 日付一覧へ") } }
                val hours = ui.transcripts
                    .filter { dateOf(it.name) == openDate }
                    .sortedByDescending { it.name }
                items(hours) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { openFile = item.name }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("${hourOf(item.name)}時台", style = MaterialTheme.typography.bodyLarge)
                            Text("${item.sizeBytes} bytes ›", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // ---- 第1階層: 日付一覧 ----
            else -> {
                val byDate = ui.transcripts.groupBy { dateOf(it.name) }
                    .toSortedMap(compareByDescending { it })
                byDate.forEach { (date, files) ->
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { openDate = date }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(date, style = MaterialTheme.typography.bodyLarge)
                                Text("${files.size} 件 ›", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
/** サーバーに保存済みの文字起こし。サーバー文字起こしモードの最新テキストもここで読む。 */
@Composable
internal fun ServerRecordsList(
    ui: UiState,
    onLoadServerTranscripts: () -> Unit,
    onLoadServerTranscript: (Long) -> Unit,
) {
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedId = openId

    LaunchedEffect(ui.account.email) {
        if (ui.account.loggedIn) onLoadServerTranscripts()
    }
    LaunchedEffect(selectedId) {
        selectedId?.let { onLoadServerTranscript(it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (selectedId == null) "サーバー記録" else "サーバー記録 › 本文",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(
                    onClick = onLoadServerTranscripts,
                    enabled = ui.account.loggedIn && !ui.serverTranscriptsLoading
                ) { Text("更新") }
            }
        }

        if (!ui.account.loggedIn) {
            item {
                Text(
                    "AIHelper にログインすると、Web と同じサーバー保存済みテキストを表示できます。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            return@LazyColumn
        }

        ui.serverTranscriptsError?.let { err ->
            item { Text("取得エラー: $err", color = MaterialTheme.colorScheme.error) }
        }

        if (selectedId != null) {
            item { TextButton(onClick = { openId = null }) { Text("← 一覧へ") } }
            val detail = ui.serverTranscriptDetail?.takeIf { it.id == selectedId }
            when {
                detail == null || ui.serverTranscriptLoadingId == selectedId -> item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> item { ServerTranscriptDetail(detail) }
            }
            return@LazyColumn
        }

        when {
            ui.serverTranscriptsLoading && ui.serverTranscripts.isEmpty() -> item {
                Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
            }
            ui.serverTranscripts.isEmpty() -> item {
                Text("サーバーに保存された記録はまだありません。", style = MaterialTheme.typography.bodySmall)
            }
            else -> items(ui.serverTranscripts) { transcript ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openId = transcript.id }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            transcript.filename,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${transcript.chars}文字 / ${formatServerTimestamp(transcript.updatedAt)}" +
                                if (transcript.analyzed) " / 解析済み" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
@Composable
internal fun ServerTranscriptDetail(detail: AiHelperClient.ServerTranscriptDetail) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(detail.filename, style = MaterialTheme.typography.titleSmall)
            Text(
                formatServerTimestamp(detail.updatedAt) + if (detail.analyzed) " / 解析済み" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (detail.summary.isNotBlank()) {
                Text("要約", style = MaterialTheme.typography.labelLarge)
                Text(detail.summary, style = MaterialTheme.typography.bodyMedium)
            }
            Text("本文", style = MaterialTheme.typography.labelLarge)
            Text(
                detail.content.ifBlank { "（空です）" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
internal fun formatServerTimestamp(value: String): String {
    if (value.isBlank()) return "-"
    return value
        .replace('T', ' ')
        .replace(Regex("\\.\\d{3}Z$"), "")
        .replace(Regex("Z$"), "")
        .take(16)
}
/** 本文と操作（共有・送信）をまとめた詳細表示。 */
@Composable
internal fun TranscriptDetail(
    item: TranscriptItem,
    ui: UiState,
    onSend: (TranscriptItem) -> Unit,
    context: android.content.Context,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall)
            val content by produceState<String?>(null, item.path, item.sizeBytes) {
                value = withContext(Dispatchers.IO) {
                    runCatching { File(item.path).readText() }.getOrElse { "読み込み失敗: ${it.message}" }
                }
            }
            if (content == null) {
                Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    content!!.ifBlank { "（空です）" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareFile(context, item.path) }) { Text("共有") }
                val sending = ui.sendingFile == item.name
                val sent = item.name in ui.sentFiles
                Button(
                    onClick = { onSend(item) },
                    enabled = ui.account.loggedIn && ui.sendingFile == null && !sent
                ) {
                    Text(when { sending -> "送信中…"; sent -> "送信済み"; else -> "サーバーへ送信" })
                }
            }
        }
    }
}
internal fun shareFile(context: android.content.Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "共有").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
