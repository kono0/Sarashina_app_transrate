package com.kono0.sarashinatranslate.translation

/**
 * 直近 [windowMs] ミリ秒の送信回数を [limit] 回以下に抑える。
 *
 * Sarashina のレート制限は 60 秒の固定ウィンドウだが、サーバー側のウィンドウの境目は分からない。
 * 「どの 60 秒を切り取っても limit 回以下」になるスライディング方式なら、固定ウィンドウでも超えない。
 */
class SlidingWindowLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val clock: () -> Long,
) {
    private val stamps = ArrayDeque<Long>()

    /** 直近のウィンドウ内で使った回数。 */
    @Synchronized
    fun used(): Int {
        prune(clock())
        return stamps.size
    }

    /** 次に送信できるまでの待ち時間 (ms)。0 ならすぐ送れる。 */
    @Synchronized
    fun waitMs(): Long {
        val now = clock()
        prune(now)
        return if (stamps.size < limit) 0 else stamps.first() + windowMs - now
    }

    /** 送信したことを記録する。 */
    @Synchronized
    fun record() {
        stamps.addLast(clock())
    }

    /** サーバーから 429 が返ったときに、今からウィンドウ 1 つ分を使い切った扱いにする。 */
    @Synchronized
    fun exhaust() {
        val now = clock()
        stamps.clear()
        repeat(limit) { stamps.addLast(now) }
    }

    private fun prune(now: Long) {
        while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
    }
}
