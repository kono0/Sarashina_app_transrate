package com.kono0.sarashinatranslate.speech

import com.kono0.sarashinatranslate.api.Lang

/** SenseVoice の言語タグと文字種から、発話が日本語か英語かを決める。 */
object LanguageDetector {

    /**
     * @param tag SenseVoice が返す言語タグ（例: `<|ja|>`、`<|en|>`、`<|zh|>`）
     * @param text 認識したテキスト
     * @return 日本語か英語。どちらとも言えなければ null
     */
    fun resolve(tag: String, text: String): Lang? {
        when (tag.trim().removePrefix("<|").removeSuffix("|>").lowercase()) {
            "ja" -> return Lang.JA
            "en" -> return Lang.EN
        }
        // 漢字だけの日本語は zh と判定されることがあるので、文字種で決め直す
        return fromScript(text)
    }

    internal fun fromScript(text: String): Lang? {
        var japanese = 0
        var latin = 0
        for (ch in text) {
            when {
                ch in '぀'..'ヿ' || ch in '一'..'鿿' || ch in 'ｦ'..'ﾟ' -> japanese++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        return when {
            japanese > 0 -> Lang.JA
            latin > 0 -> Lang.EN
            else -> null
        }
    }
}
