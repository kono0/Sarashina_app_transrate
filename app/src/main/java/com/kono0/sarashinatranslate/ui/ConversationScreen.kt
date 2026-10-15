package com.kono0.sarashinatranslate.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kono0.sarashinatranslate.R
import com.kono0.sarashinatranslate.model.ModelCatalog
import com.kono0.sarashinatranslate.model.ModelState
import com.kono0.sarashinatranslate.translation.Engine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDownloadModels: () -> Unit,
    onRetry: (Long) -> Unit,
    onRenameSpeaker: (Int, String) -> Unit,
    onClear: () -> Unit,
    onDismissMessage: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStart()
    }
    val startWithPermission = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) onStart() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 聞き取り中は画面を消さない
    val view = LocalView.current
    LaunchedEffect(state.listening) { view.keepScreenOn = state.listening == ListeningState.On }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onDismissMessage()
        }
    }

    var renaming by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sarashina 会話翻訳") },
                actions = {
                    IconButton(onClick = onClear, enabled = state.entries.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = "会話を消す")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "設定")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state.modelState == ModelState.Ready) {
                ListenButton(state.listening, onStart = startWithPermission, onStop = onStop)
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusBar(state)
            when (val ms = state.modelState) {
                ModelState.Ready -> Conversation(
                    state = state,
                    onRetry = onRetry,
                    onSpeakerClick = { renaming = it },
                )
                else -> ModelSetup(ms, onDownloadModels)
            }
        }
    }

    renaming?.let { id ->
        RenameDialog(
            initial = state.speakerName(id),
            onDismiss = { renaming = null },
            onConfirm = {
                onRenameSpeaker(id, it)
                renaming = null
            },
        )
    }
}

@Composable
private fun ListenButton(listening: ListeningState, onStart: () -> Unit, onStop: () -> Unit) {
    when (listening) {
        ListeningState.Off -> ExtendedFloatingActionButton(
            onClick = onStart,
            icon = { Icon(painterResource(R.drawable.ic_mic), contentDescription = null) },
            text = { Text("聞き取り開始") },
        )
        ListeningState.Loading -> ExtendedFloatingActionButton(
            onClick = {},
            icon = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) },
            text = { Text("準備中…") },
        )
        ListeningState.On -> ExtendedFloatingActionButton(
            onClick = onStop,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            icon = { Icon(painterResource(R.drawable.ic_stop), contentDescription = null) },
            text = { Text("停止") },
        )
    }
}

@Composable
private fun StatusBar(state: UiState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            if (!state.hasApiKey) {
                Text(
                    "APIキーが未設定です。local.properties か設定画面で入力してください。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val listening = state.listening == ListeningState.On
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                listening && state.speaking -> MaterialTheme.colorScheme.error
                                listening -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        listening && state.speaking -> "声を検出中…"
                        listening -> "聞き取り中"
                        else -> "停止中"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                val st = state.status
                val engine = st.lastEngine?.let { if (it == Engine.NANO) " ・nano使用" else "" } ?: ""
                Text(
                    "translate ${st.translateUsed}/${st.translateLimit}回/分 ・待ち${st.pending}件$engine",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelSetup(state: ModelState, onDownload: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("音声モデルの準備", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        val mb = ModelCatalog.totalBytes / 1_000_000
        Text(
            "音声認識と話者の聴き分けは端末内で行います。最初に一度だけ、約${mb}MBのモデルをダウンロードします（Wi-Fi 推奨）。",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        when (state) {
            is ModelState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "${state.fileName}  ${state.downloadedBytes / 1_000_000} / ${state.totalBytes / 1_000_000} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onDownload) { Text("もう一度ダウンロード") }
            }
            else -> Button(onClick = onDownload) { Text("ダウンロードする") }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            "音声は端末の外に送りません。翻訳には文字起こししたテキストだけを Sarashina API に送ります。\n$DATA_NOTICE",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Conversation(state: UiState, onRetry: (Long) -> Unit, onSpeakerClick: (Int) -> Unit) {
    if (state.entries.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "「聞き取り開始」を押して、日本語か英語で話してください。\n話した人ごとに分けて、原文と訳文を表示します。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.entries.size) { listState.animateScrollToItem(state.entries.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.entries, key = { it.id }) { entry ->
            EntryCard(
                entry = entry,
                speakerName = state.speakerName(entry.speakerId),
                onRetry = { onRetry(entry.id) },
                onSpeakerClick = { onSpeakerClick(entry.speakerId) },
            )
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, speakerName: String, onRetry: () -> Unit, onSpeakerClick: () -> Unit) {
    val color = speakerColor(entry.speakerId)
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(color),
            )
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        speakerName,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(onClick = onSpeakerClick),
                    )
                    Spacer(Modifier.width(8.dp))
                    entry.lang?.let {
                        Text(
                            "${it.label} → ${it.other.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(entry.original, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                TranslationText(entry.translation, onRetry)
            }
        }
    }
}

@Composable
private fun TranslationText(translation: TranslationState, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (translation) {
        TranslationState.Waiting -> Text("翻訳待ち…", color = muted, style = MaterialTheme.typography.bodyMedium)
        is TranslationState.Done -> Column {
            Text(
                translation.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (translation.engine == Engine.NANO) {
                Text("（sarashina3-nano で翻訳）", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        is TranslationState.Merged -> Text(
            "↑ 前の発話とまとめて翻訳しました",
            color = muted,
            style = MaterialTheme.typography.bodySmall,
        )
        is TranslationState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "翻訳に失敗: ${translation.reason}",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("再試行") }
        }
        TranslationState.Skipped -> Text(
            "（日本語・英語として判定できなかったため翻訳していません）",
            color = muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("話者の名前") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
