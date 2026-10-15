package com.kono0.sarashinatranslate.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sarashina API のクライアント。
 * 仕様: https://docs.sarashina.ai/api-reference/translate
 *       https://docs.sarashina.ai/api-reference/chat-completions
 */
class SarashinaClient(
    private val http: OkHttpClient,
    private val apiKey: () -> String,
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
) : TranslatorApi {

    override suspend fun translate(text: String, source: Lang, target: Lang): String {
        val body = TranslateRequest(
            text = text.take(MAX_TRANSLATE_CHARS),
            sourceLang = source.code,
            targetLang = target.code,
        )
        val response: TranslateResponse = post("translate", json.encodeToString(body))
        return response.translatedText.trim()
    }

    override suspend fun chatTranslate(text: String, source: Lang, target: Lang): String {
        val body = ChatRequest(
            model = NANO_MODEL,
            messages = listOf(
                ChatMessage("system", nanoSystemPrompt(source, target)),
                ChatMessage("user", text),
            ),
            temperature = 0.2,
        )
        val response: ChatResponse = post("chat/completions", json.encodeToString(body))
        val content = response.choices.firstOrNull()?.message?.content
            ?: throw SarashinaApiException(200, "choices が空です")
        return content.trim()
    }

    private suspend inline fun <reified T> post(path: String, jsonBody: String): T {
        val key = apiKey().trim()
        if (key.isEmpty()) throw MissingApiKeyException()
        val request = Request.Builder()
            .url(baseUrl.resolve(path) ?: error("bad path: $path"))
            .header("Authorization", "Bearer $key")
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when {
                    response.code == 429 -> throw RateLimitedException("レート制限に達しました ($path)")
                    !response.isSuccessful -> throw SarashinaApiException(response.code, text)
                    else -> json.decodeFromString<T>(text)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.sarashina.ai/v1/"
        const val NANO_MODEL = "sarashina3-nano"
        const val MAX_TRANSLATE_CHARS = 5_000

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        // 仕様にないフィールドを送ると 400 になるため、null は送らない
        internal val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        internal fun nanoSystemPrompt(source: Lang, target: Lang): String =
            "あなたはプロの翻訳者です。ユーザーが送る${source.label}の会話文を自然な${target.label}に翻訳してください。" +
                "入力が複数行のときは、各行を1行ずつ翻訳し、同じ行数で出力してください。" +
                "訳文だけを出力し、説明・注釈・引用符は付けないでください。"
    }
}

@Serializable
internal data class TranslateRequest(
    val text: String,
    @SerialName("source_lang") val sourceLang: String,
    @SerialName("target_lang") val targetLang: String,
)

@Serializable
internal data class TranslateResponse(
    @SerialName("translated_text") val translatedText: String,
)

@Serializable
internal data class ChatMessage(val role: String, val content: String?)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
)

@Serializable
internal data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
internal data class ChatChoice(val message: ChatMessage? = null)
