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

// AIチャットUI（どのタブからでも開けるダイアログとチャットパネル）。
// MainActivity.kt から分割。

/** どのタブからでも呼び出せるAIチャット。 */
@Composable
internal fun AssistantChatDialog(
    ui: UiState,
    onDismiss: () -> Unit,
    onAsk: (String) -> Unit,
    onLoadChatHistory: () -> Unit,
) {
    LaunchedEffect(ui.account.email) {
        if (ui.account.loggedIn) onLoadChatHistory()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            shadowElevation = 10.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.82f)
                .widthIn(max = 560.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("AIチャット", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
                AiChatPanel(
                    ui = ui,
                    onAsk = onAsk,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    expandMessages = true
                )
            }
        }
    }
}
/** AIチャット: 「今日の予定は？」と聞けば回答、「予定入れといて」で登録まで実行。 */
@Composable
internal fun AiChatPanel(
    ui: UiState,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
    expandMessages: Boolean = false,
) {
    var question by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!ui.account.loggedIn) {
            Text(
                "AIHelper にログインすると、予定・課題・文字起こしを見ながら相談できます。",
                style = MaterialTheme.typography.bodySmall
            )
        }
        val messageListModifier = if (expandMessages) {
            Modifier
                .weight(1f)
                .fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
        }
        LazyColumn(
            modifier = messageListModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (ui.chatHistoryLoading && ui.chatLog.isEmpty()) {
                item { Text("履歴を読み込み中…", style = MaterialTheme.typography.bodySmall) }
            } else if (ui.chatLog.isEmpty()) {
                item {
                    Text(
                        "例) 今日の予定は？ / 来週月曜10時にゼミ入れといて / 数学の宿題が出てるらしい",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            } else {
                items(ui.chatLog.takeLast(50)) { msg ->
                    ChatBubble(msg)
                }
            }
            if (ui.askInProgress) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("考え中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("メッセージ") },
                modifier = Modifier.weight(1f),
                enabled = ui.account.loggedIn && !ui.askInProgress,
                maxLines = 3
            )
            Button(
                onClick = { onAsk(question); question = "" },
                enabled = ui.account.loggedIn && !ui.askInProgress && question.isNotBlank(),
            ) {
                if (ui.askInProgress) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("送信")
                }
            }
        }
    }
}
@Composable
internal fun ChatBubble(msg: ChatMessage) {
    val background = if (msg.fromUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val color = if (msg.fromUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (msg.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Text(
            msg.text,
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(background, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color
        )
    }
}
