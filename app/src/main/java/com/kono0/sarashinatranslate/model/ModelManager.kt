package com.kono0.sarashinatranslate.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** 端末内の音声処理に使うモデルファイル。 */
data class ModelFile(val name: String, val url: String, val approxBytes: Long)

object ModelCatalog {
    val VAD = ModelFile(
        "silero_vad.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        643_854,
    )
    val SENSE_VOICE = ModelFile(
        "sense-voice.int8.onnx",
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
        239_233_841,
    )
    val SENSE_VOICE_TOKENS = ModelFile(
        "sense-voice-tokens.txt",
        "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
        315_894,
    )
    val SPEAKER = ModelFile(
        "campplus_sv_zh_en_16k.onnx",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        28_281_164,
    )

    val all = listOf(VAD, SENSE_VOICE_TOKENS, SPEAKER, SENSE_VOICE)
    val totalBytes = all.sumOf { it.approxBytes }
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val fileName: String, val downloadedBytes: Long, val totalBytes: Long) : ModelState {
        val progress: Float get() = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    }
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * モデルを [dir] にダウンロードする。途中で止まっても `.part` から続きを取る。
 * ダウンロードが終わったファイルだけを本来の名前に変えるので、名前のファイルがあれば完全にそろっている。
 */
class ModelManager(val dir: File, private val http: OkHttpClient) {

    fun fileOf(model: ModelFile) = File(dir, model.name)

    fun isReady(): Boolean = ModelCatalog.all.all { fileOf(it).exists() }

    fun download(): Flow<ModelState> = flow {
        dir.mkdirs()
        var done = ModelCatalog.all.filter { fileOf(it).exists() }.sumOf { it.approxBytes }
        for (model in ModelCatalog.all) {
            val target = fileOf(model)
            if (target.exists()) continue
            val part = File(dir, model.name + ".part")
            val already = if (part.exists()) part.length() else 0L

            val request = Request.Builder().url(model.url).apply {
                if (already > 0) header("Range", "bytes=$already-")
            }.build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${model.name}")
                val append = response.code == 206
                var written = if (append) already else 0L
                var lastEmitted = 0L
                emit(ModelState.Downloading(model.name, done + written, ModelCatalog.totalBytes))
                FileOutputStream(part, append).use { out ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            if (written - lastEmitted >= EMIT_EVERY_BYTES) {
                                lastEmitted = written
                                emit(ModelState.Downloading(model.name, done + written, ModelCatalog.totalBytes))
                            }
                        }
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("${model.name} を保存できませんでした")
            done += model.approxBytes
        }
        emit(ModelState.Ready)
    }.catch { e ->
        emit(ModelState.Failed(e.message ?: "ダウンロードに失敗しました"))
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val EMIT_EVERY_BYTES = 1L shl 20
    }
}
