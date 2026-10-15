package com.kono0.sarashinatranslate.speech

import kotlin.math.sqrt

/**
 * 話者埋め込みをその場でクラスタリングして、発話ごとに話者番号（1 始まり）を付ける。
 *
 * 各話者の埋め込みの平均（重心）とのコサイン類似度が [threshold] 以上なら同じ話者とみなし、
 * そうでなければ新しい話者を作る。
 */
class SpeakerTracker(
    @Volatile var threshold: Float = DEFAULT_THRESHOLD,
    private val maxSpeakers: Int = 8,
) {
    private class Cluster(val id: Int, val sum: FloatArray, var count: Int)

    private val clusters = mutableListOf<Cluster>()

    @get:Synchronized
    val lastSpeakerId: Int?
        get() = lastId

    private var lastId: Int? = null

    /**
     * @param allowNew false なら新しい話者は作らず、いちばん近い話者に割り当てる
     *   （短すぎる発話は埋め込みが不安定なため）
     */
    @Synchronized
    fun assign(embedding: FloatArray, allowNew: Boolean = true): Int {
        val e = normalize(embedding)
        val best = clusters.maxByOrNull { cosine(e, it.sum) }
        val similarity = best?.let { cosine(e, it.sum) } ?: -1f

        val id = when {
            best != null && similarity >= threshold -> {
                add(best, e)
                best.id
            }
            best != null && (!allowNew || clusters.size >= maxSpeakers) -> best.id
            else -> {
                val cluster = Cluster(clusters.size + 1, e.copyOf(), 1)
                clusters += cluster
                cluster.id
            }
        }
        lastId = id
        return id
    }

    @Synchronized
    fun reset() {
        clusters.clear()
        lastId = null
    }

    @get:Synchronized
    val speakerCount: Int
        get() = clusters.size

    private fun add(cluster: Cluster, e: FloatArray) {
        for (i in e.indices) cluster.sum[i] += e[i]
        cluster.count++
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f

        internal fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
            return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
        }

        /** a は正規化済み。b（重心の和）は正規化しないまま渡してよい。 */
        internal fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                nb += b[i] * b[i]
            }
            return if (nb == 0f) 0f else dot / sqrt(nb)
        }
    }
}
