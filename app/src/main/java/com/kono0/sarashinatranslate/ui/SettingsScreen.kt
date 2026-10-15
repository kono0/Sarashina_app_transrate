package com.kono0.sarashinatranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kono0.sarashinatranslate.R
import com.kono0.sarashinatranslate.settings.AppSettings

/** Sarashina サービス利用規約（https://sarashina.ai/terms）のうち、使う人が知っておくべき点。 */
internal const val DATA_NOTICE =
    "翻訳のために送ったテキストと訳文は、Sarashina のモデルの学習などに使われることがあります。" +
        "個人を特定できる情報や機密情報は話さないでください。" +
        "API キーは他の人と共有しないでください（利用規約: sarashina.ai/terms）。"

private val CREDITS = """
    翻訳: Sarashina API（sarashina3-translate / sarashina3-nano） — SB Intuitions
    音声認識: SenseVoiceSmall — FunAudioLLM / Alibaba Group（FunASR Model License）
    話者埋め込み: CAM++ — 3D-Speaker / Alibaba Group（Apache-2.0）
    音声区間検出: Silero VAD — Silero Team（MIT）
    推論ランタイム: sherpa-onnx（Apache-2.0）、ONNX Runtime（MIT）
    詳細: github.com/kono0/Sarashina_app_transrate の THIRD_PARTY_NOTICES.md
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onThresholdChange: (Float) -> Unit,
    onNanoFallbackChange: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- API キー ----
            Text("Sarashina APIキー", style = MaterialTheme.typography.titleMedium)
            var key by remember(settings.apiKeyOverride) { mutableStateOf(settings.apiKeyOverride) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text("APIキー") },
                supportingText = { Text("空欄のときは local.properties の SARASHINA_API_KEY を使います") },
            )
            Button(onClick = { onApiKeyChange(key) }, enabled = key != settings.apiKeyOverride) {
                Text("保存")
            }
            Text(
                DATA_NOTICE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ---- 話者の聴き分け ----
            Text("話者の聴き分け", style = MaterialTheme.typography.titleMedium)
            var threshold by remember(settings.speakerThreshold) { mutableFloatStateOf(settings.speakerThreshold) }
            Text("しきい値: ${"%.2f".format(threshold)}")
            Slider(
                value = threshold,
                onValueChange = { threshold = it },
                onValueChangeFinished = { onThresholdChange(threshold) },
                valueRange = 0.3f..0.8f,
            )
            Text(
                "高くすると話者を細かく分けます（同じ人が別の話者になりやすい）。" +
                    "低くすると話者をまとめます（別の人が同じ話者になりやすい）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            // ---- 翻訳 ----
            Text("翻訳", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("混雑時は sarashina3-nano で翻訳する")
                    Text(
                        "sarashina3-translate は 1 分あたり 6 回までしか使えません。" +
                            "上限に達したとき、60 回/分まで使える sarashina3-nano に切り替えて翻訳を続けます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = settings.nanoFallback, onCheckedChange = onNanoFallbackChange)
            }

            HorizontalDivider()

            // ---- クレジット（各モデルのライセンスで出典の表示が求められている） ----
            Text("使用しているモデル", style = MaterialTheme.typography.titleMedium)
            Text(
                CREDITS,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
