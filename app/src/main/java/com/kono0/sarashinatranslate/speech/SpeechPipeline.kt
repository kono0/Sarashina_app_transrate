package com.kono0.sarashinatranslate.speech

import com.kono0.sarashinatranslate.api.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

sealed interface SpeechEvent {
    /** 声を検出し始めた / 検出しなくなった。 */
    data class Activity(val speaking: Boolean) : SpeechEvent

    /** 1 発話を認識した。[lang] が null なら日本語・英語のどちらとも判定できなかった。 */
    data class Recognized(
        val speakerId: Int,
        val lang: Lang?,
        val text: String,
        val durationSec: Float,
    ) : SpeechEvent
}

/** マイク音声 → VAD で発話を切り出す → SenseVoice で認識・言語判定 → 話者埋め込みで話者を判別する。 */
class SpeechPipeline(
    private val engines: SpeechEngines,
    private val tracker: SpeakerTracker,
) {
    fun process(frames: Flow<FloatArray>): Flow<SpeechEvent> = flow {
        val vad = engines.vad
        vad.reset()
        val window = SpeechEngines.VAD_WINDOW_SIZE
        var rest = FloatArray(0)
        var speaking = false

        // 認識に時間がかかってもマイクの読み取りが止まらないように、間に大きめのバッファを挟む
        frames.buffer(FRAME_BUFFER).collect { chunk ->
            val samples = rest + chunk
            var offset = 0
            while (samples.size - offset >= window) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + window))
                offset += window
            }
            rest = samples.copyOfRange(offset, samples.size)

            val detected = vad.isSpeechDetected()
            if (detected != speaking) {
                speaking = detected
                emit(SpeechEvent.Activity(detected))
            }
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                recognize(segment.samples)?.let { emit(it) }
            }
        }
    }.flowOn(Dispatchers.Default)

    private fun recognize(samples: FloatArray): SpeechEvent.Recognized? {
        val sampleRate = AudioCapture.SAMPLE_RATE
        val durationSec = samples.size.toFloat() / sampleRate

        val stream = engines.recognizer.createStream()
        val result = try {
            stream.acceptWaveform(samples, sampleRate)
            engines.recognizer.decode(stream)
            engines.recognizer.getResult(stream)
        } finally {
            stream.release()
        }
        val text = result.text.trim()
        if (text.isEmpty()) return null

        val speakerId = embed(samples)
            ?.let { tracker.assign(it, allowNew = durationSec >= MIN_SECONDS_FOR_NEW_SPEAKER) }
            ?: tracker.lastSpeakerId
            ?: 1
        return SpeechEvent.Recognized(
            speakerId = speakerId,
            lang = LanguageDetector.resolve(result.lang, text),
            text = text,
            durationSec = durationSec,
        )
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val extractor = engines.speaker
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(samples, AudioCapture.SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    companion object {
        /** 100ms × 600 = 60 秒分 */
        private const val FRAME_BUFFER = 600

        /** これより短い発話では新しい話者を作らない（埋め込みが不安定なため）。 */
        private const val MIN_SECONDS_FOR_NEW_SPEAKER = 1.0f
    }
}
