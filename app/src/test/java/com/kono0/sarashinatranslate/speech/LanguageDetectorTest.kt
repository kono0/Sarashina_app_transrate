package com.kono0.sarashinatranslate.speech

import com.kono0.sarashinatranslate.api.Lang
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    @Test
    fun `SenseVoice の言語タグを優先する`() {
        assertEquals(Lang.JA, LanguageDetector.resolve("<|ja|>", "hello"))
        assertEquals(Lang.EN, LanguageDetector.resolve("<|en|>", "こんにちは"))
        assertEquals(Lang.EN, LanguageDetector.resolve("en", "x"))
    }

    @Test
    fun `ja・en 以外のタグは文字種で決め直す`() {
        assertEquals(Lang.JA, LanguageDetector.resolve("<|zh|>", "東京駅"))
        assertEquals(Lang.JA, LanguageDetector.resolve("<|ko|>", "カタカナ"))
        assertEquals(Lang.EN, LanguageDetector.resolve("<|zh|>", "OK thanks"))
        assertEquals(null, LanguageDetector.resolve("<|ko|>", "안녕하세요"))
        assertEquals(null, LanguageDetector.resolve("", "123 !?"))
    }
}
