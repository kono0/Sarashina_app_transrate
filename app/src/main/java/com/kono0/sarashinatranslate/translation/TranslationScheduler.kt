package com.kono0.sarashinatranslate.translation

import com.kono0.sarashinatranslate.api.Lang
import com.kono0.sarashinatranslate.api.RateLimitedException
import com.kono0.sarashinatranslate.api.TranslatorApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

/** 翻訳したい発話 1 件。 */
data class Utterance(val id: Long, val speakerId: Int, val lang: Lang, val text: String)

/** 実際に翻訳したモデル。 */
enum class Engine(val label: String) {
    TRANSLATE("sarashina3-translate"),
    NANO("sarashina3-nano"),
}

sealed interface TranslationEvent {
    val utteranceId: Long

    /**
     * 翻訳できた。まとめて送った訳文を発話ごとに分けられなかったときは、
     * 先頭の発話に全文を付け、残りの発話は [mergedIntoId] にその先頭の id を入れる。
     */
    data class Translated(
        override val utteranceId: Long,
        val text: String,
        val engine: Engine,
        val mergedIntoId: Long? = null,
    ) : TranslationEvent

    data class Failed(override val utteranceId: Long, val reason: String) : TranslationEvent
}

data class SchedulerStatus(
    val pending: Int = 0,
    val translateUsed: Int = 0,
    val translateLimit: Int = 6,
    val lastEngine: Engine? = null,
    val backoffMs: Long = 0,
)

data class SchedulerConfig(
    /** sarashina3-translate のレート制限 (https://docs.sarashina.ai/rate-limits)。 */
    val translateRpm: Int = 6,
    /** sarashina3-nano のレート制限。 */
    val nanoRpm: Int = 60,
    val windowMs: Long = 60_000,
    /** 1 リクエストにまとめる発話の最大件数。 */
    val maxBatchSize: Int = 8,
    /** 1 リクエストの最大文字数（API の上限 5000 文字より少し小さくする）。 */
    val maxBatchChars: Int = 4_500,
    /** translate の空きを待つ最大時間。これより長く待つなら nano に回す。 */
    val maxTranslateWaitMs: Long = 3_000,
    val initialBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 30_000,
)

/**
 * 発話を順番に翻訳するキュー。
 *
 * - 送信のたびに、待っている発話のうち先頭と同じ言語のものをまとめて 1 リクエストにする
 * - sarashina3-translate の 6 RPM を自前で数え、空くまで長く待つときや 429 のときは sarashina3-nano に回す
 * - nano でも 429 のときは指数バックオフする
 *
 * [run] を 1 つのコルーチンで回し、[submit] で発話を入れ、[events] で結果を受け取る。
 */
class TranslationScheduler(
    private val api: TranslatorApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: SchedulerConfig = SchedulerConfig(),
    private val nanoFallbackEnabled: () -> Boolean = { true },
) {
    private val pending = ArrayList<Utterance>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val translateLimiter = SlidingWindowLimiter(config.translateRpm, config.windowMs, clock)
    private val nanoLimiter = SlidingWindowLimiter(config.nanoRpm, config.windowMs, clock)
    @Volatile private var backoffMs = 0L
    @Volatile private var lastEngine: Engine? = null

    private val eventChannel = Channel<TranslationEvent>(Channel.UNLIMITED)
    val events: Flow<TranslationEvent> = eventChannel.receiveAsFlow()

    private val _status = MutableStateFlow(SchedulerStatus(translateLimit = config.translateRpm))
    val status: StateFlow<SchedulerStatus> = _status.asStateFlow()

    fun submit(utterance: Utterance) {
        synchronized(pending) { pending.add(utterance) }
        publishStatus()
        wake.trySend(Unit)
    }

    /** まだ送っていない発話を捨てる。 */
    fun clearPending() {
        synchronized(pending) { pending.clear() }
        publishStatus()
    }

    suspend fun run() {
        while (true) {
            val batch = nextBatch()
            if (batch.isEmpty()) {
                publishStatus()
                // 待っている間も、translate の枠が空いたことを画面に出すため定期的に更新する
                withTimeoutOrNull(STATUS_REFRESH_MS) { wake.receive() }
                continue
            }
            val engine = chooseEngine() ?: continue
            send(batch, engine)
        }
    }

    /** 送れるエンジンを返す。待つ必要があれば待ってから null を返す（待っている間に発話が増えるので、まとめ直す）。 */
    private suspend fun chooseEngine(): Engine? {
        val translateWait = translateLimiter.waitMs()
        if (translateWait == 0L) return Engine.TRANSLATE

        if (nanoFallbackEnabled() && translateWait > config.maxTranslateWaitMs) {
            val nanoWait = nanoLimiter.waitMs()
            if (nanoWait == 0L) return Engine.NANO
            publishStatus()
            delay(minOf(nanoWait, translateWait))
            return null
        }
        publishStatus()
        delay(translateWait)
        return null
    }

    private suspend fun send(batch: List<Utterance>, engine: Engine) {
        val source = batch.first().lang
        val joined = batch.joinToString("\n") { it.text.toSingleLine() }
        try {
            val translated = when (engine) {
                Engine.TRANSLATE -> {
                    translateLimiter.record()
                    publishStatus()
                    api.translate(joined, source, source.other)
                }
                Engine.NANO -> {
                    nanoLimiter.record()
                    api.chatTranslate(joined, source, source.other)
                }
            }
            backoffMs = 0
            lastEngine = engine
            remove(batch)
            splitTranslation(batch, translated, engine).forEach { eventChannel.send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RateLimitedException) {
            // 発話はキューに残したまま、次のループでもう一度送る
            when (engine) {
                Engine.TRANSLATE -> translateLimiter.exhaust()
                Engine.NANO -> {
                    backoffMs = if (backoffMs == 0L) config.initialBackoffMs
                    else minOf(backoffMs * 2, config.maxBackoffMs)
                    publishStatus()
                    delay(backoffMs)
                }
            }
        } catch (e: Exception) {
            remove(batch)
            val reason = e.message ?: e::class.simpleName ?: "不明なエラー"
            batch.forEach { eventChannel.send(TranslationEvent.Failed(it.id, reason)) }
        }
        publishStatus()
    }

    private fun nextBatch(): List<Utterance> = synchronized(pending) {
        val first = pending.firstOrNull() ?: return emptyList()
        val batch = mutableListOf(first)
        var chars = first.text.length
        for (u in pending.drop(1)) {
            if (batch.size >= config.maxBatchSize) break
            if (u.lang != first.lang) continue
            if (chars + 1 + u.text.length > config.maxBatchChars) break
            batch += u
            chars += 1 + u.text.length
        }
        batch
    }

    private fun remove(batch: List<Utterance>) {
        val ids = batch.mapTo(HashSet()) { it.id }
        synchronized(pending) { pending.removeAll { it.id in ids } }
    }

    private fun publishStatus() {
        _status.value = SchedulerStatus(
            pending = synchronized(pending) { pending.size },
            translateUsed = translateLimiter.used(),
            translateLimit = config.translateRpm,
            lastEngine = lastEngine,
            backoffMs = backoffMs,
        )
    }

    companion object {
        private const val STATUS_REFRESH_MS = 5_000L

        internal fun String.toSingleLine(): String = replace(Regex("\\s*\\n\\s*"), " ").trim()

        /** まとめて訳した結果を、行ごとに元の発話へ戻す。 */
        internal fun splitTranslation(
            batch: List<Utterance>,
            translated: String,
            engine: Engine,
        ): List<TranslationEvent.Translated> {
            if (batch.size == 1) {
                return listOf(TranslationEvent.Translated(batch[0].id, translated.trim(), engine))
            }
            val lines = translated.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == batch.size) {
                return batch.zip(lines) { u, line -> TranslationEvent.Translated(u.id, line, engine) }
            }
            val head = batch.first()
            return listOf(TranslationEvent.Translated(head.id, translated.trim(), engine)) +
                batch.drop(1).map { TranslationEvent.Translated(it.id, "", engine, mergedIntoId = head.id) }
        }
    }
}
