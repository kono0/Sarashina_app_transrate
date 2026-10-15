package com.kono0.sarashinatranslate.api

import java.io.IOException

/** Sarashina Translate API が扱える言語。 */
enum class Lang(val code: String, val label: String) {
    JA("ja", "日本語"),
    EN("en", "英語");

    val other: Lang get() = if (this == JA) EN else JA
}

/** 翻訳に使う API。テストでは偽物に差し替える。 */
interface TranslatorApi {
    /** sarashina3-translate (/v1/translate) で翻訳する。 */
    suspend fun translate(text: String, source: Lang, target: Lang): String

    /** sarashina3-nano (/v1/chat/completions) で翻訳する。translate のレート制限に達したときの予備。 */
    suspend fun chatTranslate(text: String, source: Lang, target: Lang): String
}

/** 429 Too Many Requests。 */
class RateLimitedException(message: String) : IOException(message)

/** API キーが設定されていない。 */
class MissingApiKeyException : IOException("APIキーが設定されていません")

/** 429 以外の HTTP エラー。 */
class SarashinaApiException(val code: Int, body: String) : IOException("HTTP $code: ${body.take(200)}")
