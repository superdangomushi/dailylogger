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

// 連携・設定画面（AIHelperログイン・通知・出発/雨/終電・Google/Moodle/Waseda・まとめ通知）。
// MainActivity.kt から分割。

/** 右上⚙から開く連携・設定画面。AIHelper ログインや Google/Moodle/Waseda/通知をここに集約した。 */
@Composable
internal fun AiSettingsScreen(
    ui: UiState,
    onBack: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onSetSttQuality: (String) -> Unit,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: (String) -> Unit,
    onSetDefaultGoogle: (String) -> Unit,
    onLoadCalendar: () -> Unit,
    onLoadMoodle: () -> Unit,
    onSaveMoodleUrl: (String) -> Unit,
    onSyncMoodle: () -> Unit,
    onLoadWaseda: () -> Unit,
    onSaveWaseda: (String, String) -> Unit,
    onSyncWaseda: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = onBack) { Text("← 戻る") }
            Text("連携・設定", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
        ) {
            item { AiHelperCard(ui, onLogin, onRegister, onLogout, onSetSttQuality) }
            // 通知の受け取り設定（ログイン前でも変更できる端末ローカル設定）。
            item { NotificationSettingsCard() }
            // 出発・雨・終電アラート（GPS＋天気予報。端末ローカル設定）。
            item { TravelSettingsCard() }
            // Google 連携は端末側サインインなので AIHelper ログイン前でも表示する。
            item { GoogleCalendarCard(ui, onConnectGoogle, onDisconnectGoogle, onSetDefaultGoogle, onLoadCalendar) }
            if (ui.account.loggedIn) {
                item { MoodleCard(ui, onLoadMoodle, onSaveMoodleUrl, onSyncMoodle) }
                item { WasedaCard(ui, onLoadWaseda, onSaveWaseda, onSyncWaseda) }
                item { DigestCard() }
            } else {
                item {
                    Text(
                        "AIHelper にログインすると、Moodle / Waseda 連携やまとめ通知を設定できます。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
/**
 * 通知の受け取り設定カード。マスターON/OFFと「おやすみモード」（指定時間帯は通知しない）。
 * 就寝中に締切リマインドやまとめ通知が鳴り響かないようにする。
 */
@Composable
internal fun NotificationSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { NotificationPrefs(context) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var quietEnabled by remember { mutableStateOf(prefs.quietEnabled) }
    var quietStart by remember { mutableStateOf(prefs.quietStart) }
    var quietEnd by remember { mutableStateOf(prefs.quietEnd) }

    fun pickTime(current: String, onPicked: (String) -> Unit) {
        val parts = current.split(':')
        android.app.TimePickerDialog(
            context,
            { _, h, m -> onPicked(String.format("%02d:%02d", h, m)) },
            parts.getOrNull(0)?.toIntOrNull() ?: 23,
            parts.getOrNull(1)?.toIntOrNull() ?: 0,
            true
        ).show()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("通知", style = MaterialTheme.typography.titleMedium)

            // マスタースイッチ。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("通知を受け取る", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "締切リマインドと1日のまとめ通知のON/OFF。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; prefs.enabled = it }
                )
            }

            // おやすみモード。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("おやすみモード", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "指定した時間帯は通知しません。リマインドは時間帯が明けてから届きます。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = quietEnabled,
                    enabled = enabled,
                    onCheckedChange = { quietEnabled = it; prefs.quietEnabled = it }
                )
            }

            if (enabled && quietEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { pickTime(quietStart) { quietStart = it; prefs.quietStart = it } },
                        modifier = Modifier.weight(1f)
                    ) { Text("開始 $quietStart") }
                    Text("〜")
                    OutlinedButton(
                        onClick = { pickTime(quietEnd) { quietEnd = it; prefs.quietEnd = it } },
                        modifier = Modifier.weight(1f)
                    ) { Text("終了 $quietEnd") }
                }
            }
        }
    }
}
/**
 * 出発・雨・終電アラートの設定カード（⚙連携・設定内）。
 * 自宅のGPS登録・所要時間・終電時刻と、3機能それぞれのON/OFF。
 */
@Composable
internal fun TravelSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { TravelPrefs(context) }
    var departureEnabled by remember { mutableStateOf(prefs.departureEnabled) }
    var rainEnabled by remember { mutableStateOf(prefs.rainEnabled) }
    var lastTrainEnabled by remember { mutableStateOf(prefs.lastTrainEnabled) }
    var hasHome by remember { mutableStateOf(prefs.hasHome) }
    var commuteText by remember { mutableStateOf(prefs.commuteMinutes.toString()) }
    var lastTrainTime by remember { mutableStateOf(prefs.lastTrainTime) }
    var homeMessage by remember { mutableStateOf<String?>(null) }

    fun registerHome() {
        homeMessage = "現在地を取得中…"
        TravelPrefs.fetchFreshLocation(context) { loc ->
            if (loc != null) {
                prefs.setHome(loc.latitude, loc.longitude)
                hasHome = true
                homeMessage = "自宅を登録しました（現在地）"
            } else {
                homeMessage = "現在地を取得できませんでした。屋外や窓際で再度お試しください。"
            }
        }
    }

    // 位置権限が無ければ先に要求し、許可されたら自宅登録に進む。
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) registerHome()
        else homeMessage = "位置情報の権限が必要です。"
    }

    fun pickLastTrain() {
        val parts = lastTrainTime.split(':')
        android.app.TimePickerDialog(
            context,
            { _, h, m ->
                lastTrainTime = String.format("%02d:%02d", h, m)
                prefs.lastTrainTime = lastTrainTime
            },
            parts.getOrNull(0)?.toIntOrNull() ?: 0,
            parts.getOrNull(1)?.toIntOrNull() ?: 20,
            true
        ).show()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("出発・雨・終電アラート", style = MaterialTheme.typography.titleMedium)
            Text(
                "GPSと天気予報（Open-Meteo）を使った通知です。位置情報は常時追跡せず、チェック時の最終位置だけを使います。",
                style = MaterialTheme.typography.bodySmall
            )

            // ---- 自宅の登録（出発・終電の基準点） ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    if (TravelPrefs.hasLocationPermission(context)) registerHome()
                    else locationPermLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }) { Text(if (hasHome) "自宅を登録し直す" else "現在地を自宅にする") }
                if (hasHome) {
                    Text("登録済み", style = MaterialTheme.typography.bodySmall)
                }
            }
            homeMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // ---- 出発アラート ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("出発アラート", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "今日の最初の授業・予定に間に合う出発時刻を30分前に通知。雨なら10分早め＋傘の一言。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = departureEnabled,
                    onCheckedChange = { departureEnabled = it; prefs.departureEnabled = it },
                    enabled = hasHome,
                )
            }
            if (departureEnabled) {
                OutlinedTextField(
                    value = commuteText,
                    onValueChange = { text ->
                        commuteText = text.filter { it.isDigit() }.take(3)
                        commuteText.toIntOrNull()?.let { prefs.commuteMinutes = it }
                    },
                    label = { Text("自宅から学校までの所要時間（分）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- 雨アラート ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("雨アラート", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "現在地の15分刻み予報で「まもなく降る」「まもなく止む」を通知。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = rainEnabled,
                    onCheckedChange = { rainEnabled = it; prefs.rainEnabled = it },
                )
            }

            // ---- 終電アラート ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("終電アラート", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "夜に自宅から離れた場所にいるとき、終電の60分前・20分前に通知。おやすみモード中でも鳴ります。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = lastTrainEnabled,
                    onCheckedChange = { lastTrainEnabled = it; prefs.lastTrainEnabled = it },
                    enabled = hasHome,
                )
            }
            if (lastTrainEnabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { pickLastTrain() }) {
                        Text(if (lastTrainTime.isBlank()) "終電時刻を設定" else "終電 $lastTrainTime")
                    }
                    Text("最寄り駅の終電時刻（手動設定）", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!hasHome) {
                Text(
                    "出発・終電アラートを使うには、まず自宅の登録が必要です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
/**
 * 「1日のまとめ通知」の時刻設定カード。
 * 設定した時刻（複数可）に今日の授業・予定・課題期限をまとめた通知を出す。
 */
@Composable
internal fun DigestCard() {
    val context = LocalContext.current
    var times by remember { mutableStateOf(DigestTimeStore(context).times) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("1日のまとめ通知", style = MaterialTheme.typography.titleSmall)
            Text(
                "設定した時刻に、今日の授業・予定・課題の期限をまとめて通知します（複数設定可）。",
                style = MaterialTheme.typography.bodySmall
            )
            if (times.isEmpty()) {
                Text("通知時刻は未設定です。", style = MaterialTheme.typography.bodySmall)
            }
            times.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(t, style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = {
                        val store = DigestTimeStore(context)
                        store.remove(t)
                        times = store.times
                        DailyDigestScheduler.scheduleAll(context)
                    }) { Text("削除") }
                }
            }
            OutlinedButton(onClick = {
                android.app.TimePickerDialog(
                    context,
                    { _, h, m ->
                        val store = DigestTimeStore(context)
                        store.add(String.format("%02d:%02d", h, m))
                        times = store.times
                        DailyDigestScheduler.scheduleAll(context)
                    },
                    8, 0, true
                ).show()
            }) { Text("＋ 通知時刻を追加") }
            // Android 12+ で「アラームとリマインダー」が未許可だと時刻がずれる（最大10分）ため案内する。
            val am = context.getSystemService(android.app.AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }) { Text("時刻ちょうどに通知するには「アラームとリマインダー」を許可 ›") }
            }
        }
    }
}
/** Google カレンダー連携カード。複数アカウントを連携でき、既定の登録先を選んで予定をまとめて表示。 */
@Composable
internal fun GoogleCalendarCard(
    ui: UiState,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: (String) -> Unit,
    onSetDefaultGoogle: (String) -> Unit,
    onLoadCalendar: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Google カレンダー", style = MaterialTheme.typography.titleMedium)
                if (ui.googleConnected) {
                    OutlinedButton(onClick = onLoadCalendar, enabled = !ui.googleBusy) { Text("更新") }
                }
            }
            if (!ui.googleConnected) {
                Text(
                    "連携すると、課題・予定の締切をカレンダーに登録したり、直近の予定を表示できます。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = onConnectGoogle) { Text("Google と連携") }
                // サインイン失敗の理由（OAuth 設定不備・キャンセル等）をここに表示する。
                ui.googleMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                if (ui.googleEmails.size > 1) {
                    Text("「カレンダーに追加」の登録先を選んでください。", style = MaterialTheme.typography.bodySmall)
                }
                ui.googleEmails.forEach { email ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = email == ui.googleDefault,
                            onClick = { onSetDefaultGoogle(email) }
                        )
                        Text(
                            email,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { onDisconnectGoogle(email) }) { Text("解除") }
                    }
                }
                TextButton(onClick = onConnectGoogle) { Text("アカウントを追加") }
                if (ui.calendarEvents.isEmpty()) {
                    Text(
                        if (ui.googleBusy) "読み込み中…" else "直近の予定はありません。",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    ui.calendarEvents.take(8).forEach { ev ->
                        val owner = if (ui.googleEmails.size > 1 && ev.accountEmail.isNotBlank()) {
                            "（${ev.accountEmail.substringBefore('@')}）"
                        } else ""
                        Text("・${ev.whenText}  ${ev.title}$owner", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
/** Moodle（iCal）連携カード。URL を保存し、提出物・予定を取り込む。 */
@Composable
internal fun MoodleCard(
    ui: UiState,
    onLoadMoodle: () -> Unit,
    onSaveMoodleUrl: (String) -> Unit,
    onSyncMoodle: () -> Unit,
) {
    LaunchedEffect(ui.account.email) { onLoadMoodle() }
    var url by rememberSaveable(ui.moodleUrl) { mutableStateOf(ui.moodleUrl) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Moodle 連携", style = MaterialTheme.typography.titleMedium)
            Text(
                "Moodle のカレンダー → 書き出し →「カレンダーのURLを取得」で得た iCal URL を貼り付けてください。提出物・予定が課題一覧に取り込まれます。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Moodle iCal URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSaveMoodleUrl(url) }, enabled = !ui.moodleBusy) { Text("保存") }
                OutlinedButton(onClick = onSyncMoodle, enabled = !ui.moodleBusy && url.isNotBlank()) {
                    Text("課題・予定を取り込む")
                }
            }
            if (ui.moodleBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ui.moodleMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
/** Waseda アカウント連携カード。各ユーザーが自分の Waseda ID・パスワードを保存する。 */
@Composable
internal fun WasedaCard(
    ui: UiState,
    onLoadWaseda: () -> Unit,
    onSaveWaseda: (String, String) -> Unit,
    onSyncWaseda: () -> Unit,
) {
    LaunchedEffect(ui.account.email) { onLoadWaseda() }
    var user by rememberSaveable(ui.wasedaUser) { mutableStateOf(ui.wasedaUser) }
    var password by rememberSaveable { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Waseda アカウント連携", style = MaterialTheme.typography.titleMedium)
            Text(
                "MyWaseda のログイン情報を保存すると、科目登録（時間割）を自動取得できます。" +
                    "パスワードは暗号化して保存され、時間割取得にのみ使われます。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Waseda ID（例: xxxx@akane.waseda.jp）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (ui.wasedaHasPassword) "パスワード（変更時のみ入力）" else "パスワード") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onSaveWaseda(user, password); password = "" },
                    enabled = !ui.wasedaBusy && user.isNotBlank() &&
                        (password.isNotEmpty() || ui.wasedaHasPassword)
                ) { Text("保存") }
                OutlinedButton(
                    onClick = onSyncWaseda,
                    enabled = ui.wasedaHasPassword && !ui.wasedaSyncRunning
                ) { Text("時間割を取り込む") }
                if (ui.wasedaHasPassword) {
                    Text("パスワード保存済み", style = MaterialTheme.typography.bodySmall)
                }
            }
            ui.wasedaMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // 取り込み実行中のステータスバー（サーバー側スクレイパの進行状況を表示）。
            if (ui.wasedaSyncRunning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            ui.wasedaSyncMessage?.let {
                Text(
                    if (ui.wasedaSyncRunning) "取り込み中: $it" else it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.wasedaSyncRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
/** AIHelper.jp のログイン / アカウント表示。 */
@Composable
internal fun AiHelperCard(
    ui: UiState,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onSetSttQuality: (String) -> Unit,
) {
    val account = ui.account
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AIHelper.jp 連携", style = MaterialTheme.typography.titleMedium)
            if (account.loggedIn) {
                Text("ログイン中: ${account.email}")
                Text(account.baseUrl, style = MaterialTheme.typography.bodySmall)
                SttQualitySection(ui, onSetSttQuality)
                TextButton(onClick = onLogout) { Text("ログアウト") }
            } else {
                AiHelperLoginForm(ui, onLogin, onRegister)
            }
        }
    }
}
// サーバー文字起こしのクオリティ選択肢。値はサーバー API（/api/stt-quality）と共通。
// 将来はプラン（課金）で選べるものを制限する想定だが、現時点では全員どれでも選べる。
internal val SttQualityOptions = listOf(
    "light" to "軽量（速い・精度低め）",
    "standard" to "標準（バランス）",
    "high" to "最高精度（推奨・現在の既定）",
)
/** アカウントに紐付く音声認識クオリティの選択。 */
@Composable
internal fun SttQualitySection(ui: UiState, onSetSttQuality: (String) -> Unit) {
    Column {
        Text("音声認識クオリティ", style = MaterialTheme.typography.titleSmall)
        Text(
            "サーバーで文字起こしするときの精度と速さの設定です。",
            style = MaterialTheme.typography.bodySmall
        )
        SttQualityOptions.forEach { (value, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !ui.sttQualityBusy) { onSetSttQuality(value) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = ui.sttQuality == value,
                    onClick = { onSetSttQuality(value) },
                    enabled = !ui.sttQualityBusy,
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ui.sttQualityMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
internal fun AiHelperLoginForm(
    ui: UiState,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
) {
    var baseUrl by rememberSaveable { mutableStateOf(ui.account.baseUrl) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it },
        label = { Text("サーバーURL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("メールアドレス") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("パスワード") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { onLogin(baseUrl, email, password) },
            enabled = !ui.loginInProgress,
            modifier = Modifier.weight(1f)
        ) {
            if (ui.loginInProgress) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("ログイン")
            }
        }
        OutlinedButton(
            onClick = { onRegister(baseUrl, email, password) },
            enabled = !ui.loginInProgress,
            modifier = Modifier.weight(1f)
        ) { Text("新規登録") }
    }
    ui.loginError?.let {
        Text("認証失敗: $it", color = MaterialTheme.colorScheme.error)
    }
}
