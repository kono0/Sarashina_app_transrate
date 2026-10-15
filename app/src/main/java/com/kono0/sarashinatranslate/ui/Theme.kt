package com.kono0.sarashinatranslate.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(primary = Color(0xFF2F4FA2), secondary = Color(0xFF8A6A00))
private val DarkColors = darkColorScheme(primary = Color(0xFFB3C5FF), secondary = Color(0xFFFFD166))

@Composable
fun SarashinaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** 話者ごとの色。明るい背景でも暗い背景でも見分けやすい色を選んでいる。 */
private val SpeakerColors = listOf(
    Color(0xFF1E88E5), // 青
    Color(0xFFE53935), // 赤
    Color(0xFF43A047), // 緑
    Color(0xFFFB8C00), // オレンジ
    Color(0xFF8E24AA), // 紫
    Color(0xFF00ACC1), // シアン
    Color(0xFF6D4C41), // 茶
    Color(0xFFD81B60), // ピンク
)

fun speakerColor(speakerId: Int): Color = SpeakerColors[(speakerId - 1).mod(SpeakerColors.size)]
