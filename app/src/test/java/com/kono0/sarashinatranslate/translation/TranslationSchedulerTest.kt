package com.kono0.sarashinatranslate.translation

import com.kono0.sarashinatranslate.api.Lang
import com.kono0.sarashinatranslate.api.RateLimitedException
import com.kono0.sarashinatranslate.api.TranslatorApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationSchedulerTest {

    private data class Call(val engine: Engine, val text: String, val source: Lang, val target: Lang, val at: Long)

    /** 呼ばれた内容を記録し、[responder] の結果を返す偽の API。 */
    private class FakeApi(
        private val clock: () -> Long,
        var responder: (Engine, String) -> String = { _, text -> text.uppercase() },
    ) : TranslatorApi {
        val calls = mutableListOf<Call>()

        override suspend fun translate(text: String, source: Lang, target: Lang): String {
            calls += Call(Engine.TRANSLATE, text, source, target, clock())
            return responder(Engine.TRANSLATE, text)
        }

        override suspend fun chatTranslate(text: String, source: Lang, target: Lang): String {
            calls += Call(Engine.NANO, text, source, target, clock())
            return responder(Engine.NANO, text)
        }
    }

    private class Harness(scope: TestScope, fallback: Boolean = true) {
        val clock = { scope.testScheduler.currentTime }
        val api = FakeApi(clock)
        val scheduler = TranslationScheduler(api, clock, SchedulerConfig(), nanoFallbackEnabled = { fallback })
        val events = mutableListOf<TranslationEvent>()

        init {
            scope.backgroundScope.launch { scheduler.run() }
            scope.backgroundScope.launch { scheduler.events.collect { events += it } }
        }
    }

    private var nextId = 1L
    private fun ja(text: String) = Utterance(nextId++, speakerId = 1, lang = Lang.JA, text = text)
    private fun en(text: String) = Utterance(nextId++, speakerId = 2, lang = Lang.EN, text = text)

    @Test
    fun `1件の発話を translate で日本語から英語に訳す`() = runTest {
        val h = Harness(this)
        val u = ja("こんにちは")
        h.scheduler.submit(u)
        runCurrent()

        assertEquals(listOf(Call(Engine.TRANSLATE, "こんにちは", Lang.JA, Lang.EN, 0)), h.api.calls)
        assertEquals(listOf(TranslationEvent.Translated(u.id, "こんにちは", Engine.TRANSLATE)), h.events)
    }

    @Test
    fun `待っている同じ言語の発話はまとめて送り、行ごとに戻す`() = runTest {
        val h = Harness(this)
        val a = en("hello"); val b = ja("やあ"); val c = en("how are you")
        listOf(a, b, c).forEach(h.scheduler::submit)
        runCurrent()

        assertEquals("hello\nhow are you", h.api.calls[0].text)
        assertEquals(Lang.EN, h.api.calls[0].source)
        assertEquals("やあ", h.api.calls[1].text)
        val byId = h.events.associateBy { it.utteranceId }
        assertEquals("HELLO", (byId[a.id] as TranslationEvent.Translated).text)
        assertEquals("HOW ARE YOU", (byId[c.id] as TranslationEvent.Translated).text)
    }

    @Test
    fun `訳文の行数が合わなければ先頭に全文を付け、残りはまとめた扱いにする`() = runTest {
        val h = Harness(this)
        h.api.responder = { _, _ -> "one line only" }
        val a = en("first"); val b = en("second")
        h.scheduler.submit(a); h.scheduler.submit(b)
        runCurrent()

        assertEquals(
            listOf(
                TranslationEvent.Translated(a.id, "one line only", Engine.TRANSLATE),
                TranslationEvent.Translated(b.id, "", Engine.TRANSLATE, mergedIntoId = a.id),
            ),
            h.events,
        )
    }

    @Test
    fun `translate を1分に6回使い切ったら nano に回す`() = runTest {
        val h = Harness(this)
        repeat(7) {
            h.scheduler.submit(ja("発話$it"))
            runCurrent()
            advanceTimeBy(1_000)
        }
        runCurrent()

        assertEquals(List(6) { Engine.TRANSLATE } + Engine.NANO, h.api.calls.map { it.engine })
        assertEquals(6, h.scheduler.status.value.translateUsed)
    }

    @Test
    fun `nano を使わない設定なら translate の枠が空くまで待つ`() = runTest {
        val h = Harness(this, fallback = false)
        repeat(7) {
            h.scheduler.submit(ja("発話$it"))
            runCurrent()
            advanceTimeBy(1_000)
        }
        assertEquals(6, h.api.calls.size)

        // 最初の送信 (t=0) から 60 秒たつと 7 件目が送られる
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(List(7) { Engine.TRANSLATE }, h.api.calls.map { it.engine })
        assertEquals(60_000L, h.api.calls.last().at)
    }

    @Test
    fun `translate が 429 を返したら同じ発話を nano で送り直す`() = runTest {
        val h = Harness(this)
        h.api.responder = { engine, text ->
            if (engine == Engine.TRANSLATE) throw RateLimitedException("429") else "nano:$text"
        }
        val u = ja("混雑中")
        h.scheduler.submit(u)
        runCurrent()

        assertEquals(listOf(Engine.TRANSLATE, Engine.NANO), h.api.calls.map { it.engine })
        assertEquals(listOf(TranslationEvent.Translated(u.id, "nano:混雑中", Engine.NANO)), h.events)
    }

    @Test
    fun `nano も 429 なら指数バックオフしてから送り直す`() = runTest {
        val h = Harness(this)
        var nanoFailures = 2
        h.api.responder = { engine, text ->
            when {
                engine == Engine.TRANSLATE -> throw RateLimitedException("429")
                nanoFailures-- > 0 -> throw RateLimitedException("429")
                else -> "ok:$text"
            }
        }
        h.scheduler.submit(ja("テスト"))
        runCurrent()
        assertEquals(listOf(Engine.TRANSLATE, Engine.NANO), h.api.calls.map { it.engine })

        advanceTimeBy(1_001) // 1 回目のバックオフ 1 秒
        assertEquals(3, h.api.calls.size)
        advanceTimeBy(2_001) // 2 回目のバックオフ 2 秒
        assertEquals(4, h.api.calls.size)
        assertEquals(listOf(0L, 0L, 1_000L, 3_000L), h.api.calls.map { it.at })
        assertEquals("ok:テスト", (h.events.single() as TranslationEvent.Translated).text)
        assertEquals(0L, h.scheduler.status.value.backoffMs)
    }

    @Test
    fun `429 以外のエラーは失敗として通知する`() = runTest {
        val h = Harness(this)
        h.api.responder = { _, _ -> throw java.io.IOException("network down") }
        val u = en("hi")
        h.scheduler.submit(u)
        runCurrent()

        assertEquals(listOf(TranslationEvent.Failed(u.id, "network down")), h.events)
        assertEquals(0, h.scheduler.status.value.pending)
    }

    @Test
    fun `まとめる件数と文字数には上限がある`() = runTest {
        val h = Harness(this)
        val long = "あ".repeat(3_000)
        h.scheduler.submit(ja(long))
        h.scheduler.submit(ja(long))
        runCurrent()

        assertEquals(2, h.api.calls.size)
        assertTrue(h.api.calls.all { it.text.length <= 4_500 })
    }

    @Test
    fun `改行を含む発話は1行にしてから送る`() {
        with(TranslationScheduler.Companion) {
            assertEquals("a b c", "a\n b \nc".toSingleLine())
        }
        val split = TranslationScheduler.splitTranslation(listOf(ja("x")), "  X  ", Engine.TRANSLATE)
        assertEquals("X", split.single().text)
        assertNull(split.single().mergedIntoId)
    }
}
