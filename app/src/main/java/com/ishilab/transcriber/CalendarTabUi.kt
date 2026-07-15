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

// 予定タブのUI（月カレンダー・出発/天気/終電の状況カード）。
// MainActivity.kt から分割。

internal data class CalItem(
    val date: LocalDate,
    val time: String,
    val title: String,
    val task: AiHelperClient.Task? = null,
)
// DowJa / PeriodTimes / courseTermRange / courseOccursOn / courseTime は
// まとめ通知と共用のため CourseSchedule.kt に移動した。

/** 月カレンダー。日付をタップするとその日の予定・時間・（あれば）要約を表示。 */
@Composable
internal fun CalendarTab(
    ui: UiState,
    onUpdateTask: (AiHelperClient.Task, String, String, String, String) -> Unit,
    onDeleteTask: (AiHelperClient.Task) -> Unit,
    onLoadDaySummary: (String) -> Unit,
) {
    var ymStr by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedStr by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var editingTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    val ym = runCatching { YearMonth.parse(ymStr) }.getOrDefault(YearMonth.now())
    val selected = runCatching { LocalDate.parse(selectedStr) }.getOrDefault(LocalDate.now())
    val editingTask = editingTaskId?.let { id -> ui.tasks.firstOrNull { it.id == id } }

    if (editingTask != null) {
        TaskEditDialog(
            task = editingTask,
            saving = ui.taskActionInProgressId == editingTask.id,
            onDismiss = { editingTaskId = null },
            onSave = { type, content, details, deadline ->
                onUpdateTask(editingTask, type, content, details, deadline)
                editingTaskId = null
            },
            onDelete = {
                onDeleteTask(editingTask)
                editingTaskId = null
            },
        )
    }

    // 課題・予定 + Google カレンダー予定 + Waseda 授業予定を日付ごとにまとめる。
    val byDate = remember(ui.tasks, ui.calendarEvents, ui.courses, ymStr) {
        val list = mutableListOf<CalItem>()
        ui.tasks.forEach { t ->
            val dl = t.deadline
            if (!dl.isNullOrBlank()) {
                val d = runCatching { LocalDate.parse(dl.take(10)) }.getOrNull()
                if (d != null) {
                    val norm = dl.replace('T', ' ')
                    val time = if (!t.dateOnly && norm.length >= 16) norm.substring(11, 16) else ""
                    val label = if (t.type == "yotei") "予定" else "課題"
                    list.add(CalItem(d, time, "[$label] ${t.content}", t))
                }
            }
        }
        ui.calendarEvents.forEach { ev ->
            if (ev.startMillis > 0) {
                val d = Instant.ofEpochMilli(ev.startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                val norm = ev.whenText.replace('T', ' ')
                val start = if (norm.length >= 16) norm.substring(11, 16) else ""
                val endNorm = ev.endText.replace('T', ' ')
                val end = if (endNorm.length >= 16) endNorm.substring(11, 16) else ""
                val time = if (start.isNotBlank() && end.isNotBlank()) "$start〜$end" else start
                list.add(CalItem(d, time, "[カレンダー] ${ev.title}"))
            }
        }
        for (day in 1..ym.lengthOfMonth()) {
            val date = ym.atDay(day)
            ui.courses.forEach { c ->
                if (courseOccursOn(c, date)) {
                    val room = if (c.room.isNotBlank()) " (${c.room})" else ""
                    val period = c.period?.let { "${it}限 " }.orEmpty()
                    list.add(CalItem(date, courseTime(c), "[授業] $period${c.name}$room"))
                }
            }
        }
        list.groupBy { it.date }
    }

    LaunchedEffect(selectedStr) { onLoadDaySummary(selectedStr) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 出発・雨・終電の現在状況（機能を1つでも有効にしていれば表示）。
        item { TravelStatusCard() }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { ymStr = ym.minusMonths(1).toString() }) { Text("‹ 前月") }
                Text("${ym.year}年${ym.monthValue}月", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { ymStr = ym.plusMonths(1).toString() }) { Text("翌月 ›") }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("日", "月", "火", "水", "木", "金", "土").forEach {
                    Text(
                        it, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // 週ごとの行
        val lead = ym.atDay(1).dayOfWeek.value % 7 // 月=1..日=7 → 日=0 起点
        val cells = buildList<LocalDate?> {
            repeat(lead) { add(null) }
            for (d in 1..ym.lengthOfMonth()) add(ym.atDay(d))
            while (size % 7 != 0) add(null)
        }
        items(cells.chunked(7)) { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    if (date == null) {
                        Box(modifier = Modifier.weight(1f).height(44.dp))
                    } else {
                        val isSel = date == selected
                        val has = byDate.containsKey(date)
                        Box(
                            modifier = Modifier
                                .weight(1f).height(44.dp).padding(2.dp)
                                .background(
                                    if (isSel) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedStr = date.toString() },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${date.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
                                if (has) {
                                    Box(
                                        modifier = Modifier.size(5.dp).background(
                                            MaterialTheme.colorScheme.primary, RoundedCornerShape(50)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 選択日の詳細
        item {
            Text(
                "${selected.monthValue}月${selected.dayOfMonth}日 の予定",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (ui.coursesLoading) {
            item { Text("時間割を読み込み中…", style = MaterialTheme.typography.bodySmall) }
        }
        ui.coursesError?.let { err ->
            item { Text("時間割の取得エラー: $err", color = MaterialTheme.colorScheme.error) }
        }
        val dayItems = (byDate[selected] ?: emptyList()).sortedBy { it.time.ifBlank { "99:99" } }
        if (dayItems.isEmpty()) {
            item { Text("予定はありません。", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(dayItems) { it2 ->
                val task = it2.task
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (task != null) Modifier.clickable { editingTaskId = task.id } else Modifier)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(it2.time.ifBlank { "終日" }, style = MaterialTheme.typography.labelLarge)
                        Text(it2.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        if (task != null) {
                            TextButton(
                                onClick = { editingTaskId = task.id },
                                enabled = ui.taskActionInProgressId != task.id,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text("編集") }
                        }
                    }
                }
            }
        }
        // その日の要約（あれば）
        if (ui.daySummaryDay == selectedStr && !ui.daySummary.isNullOrBlank()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("この日の要約", style = MaterialTheme.typography.titleSmall)
                        Text(ui.daySummary!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
/**
 * 予定タブ上部の「出発・天気・終電」状況カード。
 * 1分ごとに再計算（天気は10分キャッシュ）。全機能OFFなら何も表示しない。
 */
@Composable
internal fun TravelStatusCard() {
    val context = LocalContext.current
    var enabledAny by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<TravelAssistant.Status?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            val prefs = TravelPrefs(context)
            enabledAny = prefs.departureEnabled || prefs.rainEnabled || prefs.lastTrainEnabled
            if (enabledAny) {
                status = withContext(Dispatchers.IO) {
                    runCatching { TravelAssistant.status(context) }.getOrNull()
                }
            }
            delay(60_000)
        }
    }

    if (!enabledAny) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("出発・天気", style = MaterialTheme.typography.titleSmall)
                val s = status
                if (s?.temp != null) {
                    Text(
                        "${if (s.rainingNow) "☔ " else ""}%.0f℃".format(s.temp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            val s = status
            if (s == null) {
                Text("読み込み中…", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            // 出発の目安。
            if (s.nextEventTitle != null && s.departureAt != null) {
                Text("次: ${s.nextEventTitle}（${s.nextEventStart}〜）", style = MaterialTheme.typography.bodyMedium)
                val inMin = s.departureInMin ?: 0
                Text(
                    when {
                        inMin > 0 -> "⏰ ${s.departureAt} に出発（あと${inMin}分）"
                        inMin > -10 -> "⏰ 出発時間です！（目安 ${s.departureAt}）"
                        else -> "⏰ 出発目安 ${s.departureAt} を過ぎています"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (inMin <= 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                if (s.umbrella) {
                    Text("☔ 降水確率${s.precipProb}%。傘を持って。", style = MaterialTheme.typography.bodySmall)
                }
            }
            // 雨の降り出し・止み。
            s.rainStartsAt?.let { Text("☔ ${it}ごろから雨の予報", style = MaterialTheme.typography.bodySmall) }
            s.rainStopsAt?.let { Text("☂ ${it}ごろに止む見込み", style = MaterialTheme.typography.bodySmall) }
            // 終電カウントダウン（外出中のみ強調）。
            if (s.lastTrainAt != null && s.lastTrainInMin != null) {
                val away = s.awayFromHome == true
                Text(
                    "🚃 終電 ${s.lastTrainAt} まであと${s.lastTrainInMin}分" + if (away) "（外出中）" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (away && s.lastTrainInMin <= 60) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
