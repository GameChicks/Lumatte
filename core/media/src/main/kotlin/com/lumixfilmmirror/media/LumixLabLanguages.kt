package com.lumixfilmmirror.media

import android.content.Context

/** Service values and Android qualifiers recovered from Lab 3.1.0 app_lang, not guessed BCP-47 conversion. */
object LumixLabLanguages {
    val supportedCodes: Set<String> get() = setOf("en", "de", "es", "fr", "it", "ja", "ko", "nl", "ru", "zh-CN", "zh-TW")

    /** Use a frontend's configured Context for per-app locale overrides. No network request. */
    fun forContext(context: Context): String = context.getString(R.string.lumix_lab_language)
}
