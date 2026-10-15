package com.kono0.sarashinatranslate.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SarashinaClientTest {

    private lateinit var server: MockWebServer
    private var apiKey = "test-key"
    private lateinit var client: SarashinaClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SarashinaClient(OkHttpClient(), apiKey = { apiKey }, baseUrl = server.url("/v1/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `translate は仕様どおりのリクエストを送り translated_text を返す`() = runTest {
        server.enqueue(MockResponse().setBody("""{"translated_text":" Hello. ","extra":1}"""))

        val result = client.translate("こんにちは。", Lang.JA, Lang.EN)

        assertEquals("Hello.", result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/translate", request.path)
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("text", "source_lang", "target_lang"), body.keys)
        assertEquals("こんにちは。", body["text"]!!.jsonPrimitive.content)
        assertEquals("ja", body["source_lang"]!!.jsonPrimitive.content)
        assertEquals("en", body["target_lang"]!!.jsonPrimitive.content)
    }

    @Test
    fun `chatTranslate は sarashina3-nano に送り、choices の content を返す`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"こんにちは","tool_calls":[]},"finish_reason":"stop"}],"usage":{}}""",
            ),
        )

        val result = client.chatTranslate("Hello", Lang.EN, Lang.JA)

        assertEquals("こんにちは", result)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("model", "messages", "temperature"), body.keys)
        assertEquals("sarashina3-nano", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `429 は RateLimitedException になる`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limit"}"""))
        try {
            client.translate("x", Lang.EN, Lang.JA)
            fail("例外になるはず")
        } catch (e: RateLimitedException) {
            // OK
        }
    }

    @Test
    fun `400 は SarashinaApiException になる`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))
        try {
            client.translate("x", Lang.EN, Lang.JA)
            fail("例外になるはず")
        } catch (e: SarashinaApiException) {
            assertEquals(400, e.code)
        }
    }

    @Test
    fun `APIキーが空ならリクエストを送らない`() = runTest {
        apiKey = "  "
        try {
            client.translate("x", Lang.EN, Lang.JA)
            fail("例外になるはず")
        } catch (e: MissingApiKeyException) {
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `5000文字を超える text は切り詰める`() = runTest {
        server.enqueue(MockResponse().setBody("""{"translated_text":"ok"}"""))
        client.translate("a".repeat(6_000), Lang.EN, Lang.JA)

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(5_000, body["text"]!!.jsonPrimitive.content.length)
    }
}
