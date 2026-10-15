package com.kono0.sarashinatranslate.speech

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SpeakerTrackerTest {

    private val random = Random(42)

    /** 基準の声 [base] に小さな揺らぎを足した埋め込み。 */
    private fun voice(base: FloatArray, noise: Float = 0.1f) =
        FloatArray(base.size) { base[it] + (random.nextFloat() - 0.5f) * 2 * noise }

    private fun randomBase(dim: Int = 192) = FloatArray(dim) { random.nextFloat() - 0.5f }

    @Test
    fun `同じ声は同じ話者、違う声は新しい話者になる`() {
        val tracker = SpeakerTracker(threshold = 0.5f)
        val alice = randomBase()
        val bob = randomBase()

        assertEquals(1, tracker.assign(voice(alice)))
        assertEquals(2, tracker.assign(voice(bob)))
        assertEquals(1, tracker.assign(voice(alice)))
        assertEquals(2, tracker.assign(voice(bob)))
        assertEquals(2, tracker.speakerCount)
    }

    @Test
    fun `allowNew が false ならいちばん近い既存の話者に割り当てる`() {
        val tracker = SpeakerTracker(threshold = 0.5f)
        val alice = randomBase()
        tracker.assign(voice(alice))

        assertEquals(1, tracker.assign(voice(randomBase()), allowNew = false))
        assertEquals(1, tracker.speakerCount)
    }

    @Test
    fun `話者数の上限を超えたら近い話者に割り当てる`() {
        val tracker = SpeakerTracker(threshold = 0.9f, maxSpeakers = 2)
        tracker.assign(randomBase())
        tracker.assign(randomBase())

        val third = tracker.assign(randomBase())
        assertEquals(2, tracker.speakerCount)
        assert(third in 1..2)
    }

    @Test
    fun `reset で話者を忘れる`() {
        val tracker = SpeakerTracker()
        tracker.assign(randomBase())
        tracker.assign(randomBase())
        tracker.reset()

        assertEquals(0, tracker.speakerCount)
        assertEquals(null, tracker.lastSpeakerId)
        assertEquals(1, tracker.assign(randomBase()))
    }

    @Test
    fun `コサイン類似度`() {
        val a = SpeakerTracker.normalize(floatArrayOf(1f, 0f))
        assertEquals(1f, SpeakerTracker.cosine(a, floatArrayOf(3f, 0f)), 1e-6f)
        assertEquals(0f, SpeakerTracker.cosine(a, floatArrayOf(0f, 2f)), 1e-6f)
        assertEquals(-1f, SpeakerTracker.cosine(a, floatArrayOf(-1f, 0f)), 1e-6f)
    }
}
