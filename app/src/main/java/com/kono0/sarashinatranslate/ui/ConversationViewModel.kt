package com.kono0.sarashinatranslate.ui

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kono0.sarashinatranslate.BuildConfig
import com.kono0.sarashinatranslate.api.Lang
import com.kono0.sarashinatranslate.api.SarashinaClient
import com.kono0.sarashinatranslate.model.ModelManager
import com.kono0.sarashinatranslate.model.ModelState
import com.kono0.sarashinatranslate.settings.AppSettings
import com.kono0.sarashinatranslate.settings.SettingsRepository
import com.kono0.sarashinatranslate.speech.AudioCapture
import com.kono0.sarashinatranslate.speech.SpeakerTracker
import com.kono0.sarashinatranslate.speech.SpeechEngines
import com.kono0.sarashinatranslate.speech.SpeechEvent
import com.kono0.sarashinatranslate.speech.SpeechPipeline
import com.kono0.sarashinatranslate.translation.Engine
import com.kono0.sarashinatranslate.translation.SchedulerStatus
import com.kono0.sarashinatranslate.translation.TranslationEvent
import com.kono0.sarashinatranslate.translation.TranslationScheduler
import com.kono0.sarashinatranslate.translation.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** 会話の 1 発話。 */
data class Entry(
    val id: Long,
    val speakerId: Int,
    val lang: Lang?,
    val original: String,
    val translation: TranslationState,
)

sealed interface TranslationState {
    data object Waiting : TranslationState
    data class Done(val text: String, val engine: Engine) : TranslationState
    /** 前の発話とまとめて訳され、行ごとに分けられなかった。 */
    data class Merged(val intoId: Long) : TranslationState
    data class Failed(val reason: String) : TranslationState
    /** 日本語・英語のどちらとも判定できなかったので訳していない。 */
    data object Skipped : TranslationState
}

enum class ListeningState { Off, Loading, On }

data class UiState(
    val modelState: ModelState = ModelState.Missing,
    val listening: ListeningState = ListeningState.Off,
    val speaking: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val speakerNames: Map<Int, String> = emptyMap(),
    val status: SchedulerStatus = SchedulerStatus(),
    val settings: AppSettings = AppSettings(),
    val hasApiKey: Boolean = false,
    val message: String? = null,
) {
    fun speakerName(id: Int): String = speakerNames[id] ?: "話者$id"
}

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = SettingsRepository(app)
    @Volatile private var settings = AppSettings()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val models = ModelManager(File(app.filesDir, "models"), http)
    private val api = SarashinaClient(http, apiKey = ::currentApiKey)
    private val scheduler = TranslationScheduler(api, nanoFallbackEnabled = { settings.nanoFallback })
    private val tracker = SpeakerTracker()

    private var engines: SpeechEngines? = null
    private var listenJob: Job? = null
    private var downloadJob: Job? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(
        UiState(
            modelState = if (models.isReady()) ModelState.Ready else ModelState.Missing,
            hasApiKey = BuildConfig.SARASHINA_API_KEY.isNotBlank(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                settings = s
                tracker.threshold = s.speakerThreshold
                _state.update { it.copy(settings = s, hasApiKey = currentApiKey().isNotBlank()) }
            }
        }
        viewModelScope.launch { scheduler.run() }
        viewModelScope.launch { scheduler.events.collect(::onTranslationEvent) }
        viewModelScope.launch { scheduler.status.collect { st -> _state.update { it.copy(status = st) } } }
    }

    private fun currentApiKey(): String =
        settings.apiKeyOverride.ifBlank { BuildConfig.SARASHINA_API_KEY }

    // ---- モデル ----

    fun downloadModels() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            models.download().collect { ms -> _state.update { it.copy(modelState = ms) } }
        }
    }

    // ---- 録音 ----

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (listenJob?.isActive == true || _state.value.modelState != ModelState.Ready) return
        listenJob = viewModelScope.launch {
            _state.update { it.copy(listening = ListeningState.Loading, message = null) }
            try {
                val eng = engines ?: withContext(Dispatchers.Default) { SpeechEngines.load(models) }
                    .also { engines = it }
                _state.update { it.copy(listening = ListeningState.On) }
                SpeechPipeline(eng, tracker).process(AudioCapture().frames()).collect(::onSpeechEvent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(message = "音声処理を開始できませんでした: ${e.message}") }
            } finally {
                _state.update { it.copy(listening = ListeningState.Off, speaking = false) }
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
    }

    private fun onSpeechEvent(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Activity -> _state.update { it.copy(speaking = event.speaking) }
            is SpeechEvent.Recognized -> {
                val id = nextId++
                val lang = event.lang
                val entry = Entry(
                    id = id,
                    speakerId = event.speakerId,
                    lang = lang,
                    original = event.text,
                    translation = if (lang == null) TranslationState.Skipped else TranslationState.Waiting,
                )
                _state.update { it.copy(entries = it.entries + entry) }
                if (lang != null) scheduler.submit(Utterance(id, event.speakerId, lang, event.text))
            }
        }
    }

    // ---- 翻訳 ----

    private fun onTranslationEvent(event: TranslationEvent) {
        val newState = when (event) {
            is TranslationEvent.Translated ->
                event.mergedIntoId?.let { TranslationState.Merged(it) }
                    ?: TranslationState.Done(event.text, event.engine)
            is TranslationEvent.Failed -> TranslationState.Failed(event.reason)
        }
        _state.update { s ->
            s.copy(entries = s.entries.map { if (it.id == event.utteranceId) it.copy(translation = newState) else it })
        }
    }

    fun retry(entryId: Long) {
        val entry = _state.value.entries.firstOrNull { it.id == entryId } ?: return
        val lang = entry.lang ?: return
        _state.update { s ->
            s.copy(entries = s.entries.map { if (it.id == entryId) it.copy(translation = TranslationState.Waiting) else it })
        }
        scheduler.submit(Utterance(entry.id, entry.speakerId, lang, entry.original))
    }

    // ---- 会話の操作 ----

    fun renameSpeaker(id: Int, name: String) {
        _state.update { s ->
            val names = if (name.isBlank()) s.speakerNames - id else s.speakerNames + (id to name.trim())
            s.copy(speakerNames = names)
        }
    }

    fun clearConversation() {
        scheduler.clearPending()
        tracker.reset()
        _state.update { it.copy(entries = emptyList(), speakerNames = emptyMap()) }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- 設定 ----

    fun setApiKey(value: String) = viewModelScope.launch { settingsRepository.setApiKey(value) }

    fun setSpeakerThreshold(value: Float) = viewModelScope.launch { settingsRepository.setSpeakerThreshold(value) }

    fun setNanoFallback(value: Boolean) = viewModelScope.launch { settingsRepository.setNanoFallback(value) }

    override fun onCleared() {
        val eng = engines
        val job = listenJob
        if (job == null || job.isCompleted) eng?.release() else job.invokeOnCompletion { eng?.release() }
    }
}
