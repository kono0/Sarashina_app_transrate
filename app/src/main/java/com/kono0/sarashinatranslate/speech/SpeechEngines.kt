package com.kono0.sarashinatranslate.speech

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.kono0.sarashinatranslate.model.ModelCatalog
import com.kono0.sarashinatranslate.model.ModelFile
import com.kono0.sarashinatranslate.model.ModelManager

/** sherpa-onnx のモデル一式（VAD・音声認識・話者埋め込み）。読み込みに数秒かかるので使い回す。 */
class SpeechEngines private constructor(
    val vad: Vad,
    val recognizer: OfflineRecognizer,
    val speaker: SpeakerEmbeddingExtractor,
) {
    fun release() {
        vad.release()
        recognizer.release()
        speaker.release()
    }

    companion object {
        const val VAD_WINDOW_SIZE = 512

        fun load(models: ModelManager): SpeechEngines {
            val path = { m: ModelFile -> models.fileOf(m).absolutePath }

            val vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = path(ModelCatalog.VAD),
                        threshold = 0.5f,
                        // 会話の息継ぎで切れすぎないよう、少し長めの無音で区切る
                        minSilenceDuration = 0.5f,
                        minSpeechDuration = 0.25f,
                        windowSize = VAD_WINDOW_SIZE,
                        maxSpeechDuration = 12f,
                    ),
                    sampleRate = AudioCapture.SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = path(ModelCatalog.SENSE_VOICE),
                            language = "", // 自動判定
                            useInverseTextNormalization = true,
                        ),
                        tokens = path(ModelCatalog.SENSE_VOICE_TOKENS),
                        numThreads = 2,
                    ),
                ),
            )
            val speaker = SpeakerEmbeddingExtractor(
                config = SpeakerEmbeddingExtractorConfig(
                    model = path(ModelCatalog.SPEAKER),
                    numThreads = 2,
                ),
            )
            return SpeechEngines(vad, recognizer, speaker)
        }
    }
}
