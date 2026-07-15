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
/** アプリ全体の配色（Web と揃えたインディゴ基調）。 */
private val AppColorScheme = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFF7C3AED),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF2F7),
)
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        // 録音していないときでも「締切が近い予定・課題」を定期通知する。
        com.ishilab.transcriber.service.ReminderReceiver.schedule(this)
        // 設定済みの「1日のまとめ通知」アラームを貼り直す。
        com.ishilab.transcriber.service.DailyDigestScheduler.scheduleAll(this)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ui by viewModel.ui.collectAsState()
                    val service by AudioCaptureService.state.collectAsStateWithLifecycle()
                    // アカウント選択（複数連携可）: システムの Google アカウント選択画面を使う。
                    val googleLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result -> viewModel.onGoogleAccountPicked(result.data) }
                    // 初回利用許可（同意画面）: 許可後にカレンダーを読み直す。
                    val consentLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { viewModel.loadCalendar() }
                    LaunchedEffect(ui.googleConsentIntent) {
                        ui.googleConsentIntent?.let { intent ->
                            viewModel.consentIntentLaunched()
                            consentLauncher.launch(intent)
                        }
                    }
                    MainScreen(
                        ui = ui,
                        service = service,
                        onDownload = viewModel::download,
                        onSelectModel = viewModel::selectModel,
                        onSetServerTranscribe = viewModel::setServerTranscribe,
                        onStart = { AudioCaptureService.start(this) },
                        onStop = { AudioCaptureService.stop(this) },
                        onRefresh = viewModel::refresh,
                        onLogin = viewModel::login,
                        onRegister = viewModel::register,
                        onLogout = viewModel::logout,
                        onSend = viewModel::sendToServer,
                        onLoadServerTranscripts = viewModel::loadServerTranscripts,
                        onLoadServerTranscript = viewModel::loadServerTranscript,
                        onAsk = viewModel::ask,
                        onLoadChatHistory = viewModel::loadChatHistory,
                        onLoadTasks = { viewModel.loadTasks() },
                        onToggleTask = viewModel::toggleTaskDone,
                        onUpdateTask = viewModel::updateTask,
                        onDeleteTask = viewModel::deleteTask,
                        onSetShowDone = { viewModel.loadTasks(it) },
                        onLoadSummary = viewModel::loadSummary,
                        onGenerateSummary = viewModel::generateSummary,
                        onConnectGoogle = {
                            googleLauncher.launch(GoogleCalendarClient.chooseAccountIntent())
                        },
                        onDisconnectGoogle = viewModel::disconnectGoogle,
                        onSetDefaultGoogle = viewModel::setDefaultGoogle,
                        onLoadCalendar = viewModel::loadCalendar,
                        onAddToCalendar = viewModel::addTaskToCalendar,
                        onSetSttQuality = viewModel::setSttQuality,
                        onLoadMoodle = viewModel::loadMoodle,
                        onSaveMoodleUrl = viewModel::saveMoodleUrl,
                        onSyncMoodle = viewModel::syncMoodle,
                        onLoadWaseda = viewModel::loadWaseda,
                        onSaveWaseda = viewModel::saveWaseda,
                        onSyncWaseda = viewModel::syncWaseda,
                        onLoadDaySummary = viewModel::loadDaySummary,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.startForegroundSync()
        viewModel.refreshGoogle()
    }

    override fun onPause() {
        viewModel.stopForegroundSync()
        super.onPause()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val toRequest = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    ui: UiState,
    service: ServiceState,
    onDownload: (WhisperModel) -> Unit,
    onSelectModel: (WhisperModel) -> Unit,
    onSetServerTranscribe: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onLogin: (String, String, String) -> Unit,
    onRegister: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onSend: (TranscriptItem) -> Unit,
    onLoadServerTranscripts: () -> Unit,
    onLoadServerTranscript: (Long) -> Unit,
    onAsk: (String) -> Unit,
    onLoadChatHistory: () -> Unit,
    onLoadTasks: () -> Unit,
    onToggleTask: (AiHelperClient.Task) -> Unit,
    onUpdateTask: (AiHelperClient.Task, String, String, String, String) -> Unit,
    onDeleteTask: (AiHelperClient.Task) -> Unit,
    onSetShowDone: (Boolean) -> Unit,
    onLoadSummary: () -> Unit,
    onGenerateSummary: () -> Unit,
    onConnectGoogle: () -> Unit,
    onDisconnectGoogle: (String) -> Unit,
    onSetDefaultGoogle: (String) -> Unit,
    onLoadCalendar: () -> Unit,
    onAddToCalendar: (AiHelperClient.Task) -> Unit,
    onSetSttQuality: (String) -> Unit,
    onLoadMoodle: () -> Unit,
    onSaveMoodleUrl: (String) -> Unit,
    onSyncMoodle: () -> Unit,
    onLoadWaseda: () -> Unit,
    onSaveWaseda: (String, String) -> Unit,
    onSyncWaseda: () -> Unit,
    onLoadDaySummary: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("常時録音・ローカル文字起こし") }) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("録音") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("記録") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("予定") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("AI") })
                }
                when (tab) {
                    0 -> RecordingTab(ui, service, onDownload, onSelectModel, onSetServerTranscribe, onStart, onStop)
                    1 -> RecordsTab(ui, onRefresh, onSend, onLoadServerTranscripts, onLoadServerTranscript)
                    2 -> CalendarTab(ui, onUpdateTask, onDeleteTask, onLoadDaySummary)
                    else -> AiTab(
                        ui, onLogin, onRegister, onLogout, onAsk, onLoadTasks, onToggleTask,
                        onUpdateTask, onDeleteTask, onSetShowDone, onLoadSummary, onGenerateSummary,
                        onConnectGoogle, onDisconnectGoogle, onSetDefaultGoogle, onLoadCalendar, onAddToCalendar,
                        onSetSttQuality,
                        onLoadMoodle, onSaveMoodleUrl, onSyncMoodle, onLoadWaseda, onSaveWaseda, onSyncWaseda
                    )
                }
            }
        }
        // 音声→テキスト変換中は右上に小さく表示（操作は妨げない）。どの区間かと進捗も出す。
        if (service.transcribing) {
            TranscribingBadge(service)
        }
        FloatingActionButton(
            onClick = { chatOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Text("AI", style = MaterialTheme.typography.titleMedium)
        }
        if (chatOpen) {
            AssistantChatDialog(
                ui = ui,
                onDismiss = { chatOpen = false },
                onAsk = onAsk,
                onLoadChatHistory = onLoadChatHistory,
            )
        }
    }
}
/** 文字起こし処理中を右上にちょこんと示す小さなインジケータ（画面操作はブロックしない）。 */
@Composable
private fun BoxScope.TranscribingBadge(service: ServiceState) {
    val pct = (service.transcribeProgress * 100).toInt()
    val label = service.transcribeLabel
    Surface(
        shape = RoundedCornerShape(50),
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 10.dp, end = 10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                if (label != null) "$label 処理中 $pct%" else "処理中 $pct%",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
