package com.kono0.sarashinatranslate.speech

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** マイクから 16kHz モノラルの音声を 100ms ずつ読み出す。 */
class AudioCapture(private val sampleRate: Int = SAMPLE_RATE) {

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(): Flow<FloatArray> = flow {
        val chunk = sampleRate / 10
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunk * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "マイクを開けませんでした" }
        try {
            record.startRecording()
            val buffer = ShortArray(chunk)
            while (currentCoroutineContext().isActive) {
                val n = record.read(buffer, 0, buffer.size)
                if (n > 0) emit(FloatArray(n) { buffer[it] / 32768f })
            }
        } finally {
            record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
