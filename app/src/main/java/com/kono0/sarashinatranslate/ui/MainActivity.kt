package com.kono0.sarashinatranslate.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SarashinaTheme {
                val vm: ConversationViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by rememberSaveable { mutableStateOf(false) }

                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(
                        settings = state.settings,
                        onBack = { showSettings = false },
                        onApiKeyChange = vm::setApiKey,
                        onThresholdChange = vm::setSpeakerThreshold,
                        onNanoFallbackChange = vm::setNanoFallback,
                    )
                } else {
                    ConversationScreen(
                        state = state,
                        onStart = vm::startListening,
                        onStop = vm::stopListening,
                        onDownloadModels = vm::downloadModels,
                        onRetry = vm::retry,
                        onRenameSpeaker = vm::renameSpeaker,
                        onClear = vm::clearConversation,
                        onDismissMessage = vm::dismissMessage,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
