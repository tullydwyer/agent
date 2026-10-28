package dev.pixelagent.wakeword

import android.content.Context

class WakeWordSettings(context: Context) {
    private val prefs = context.getSharedPreferences("hands_free", Context.MODE_PRIVATE)
    private val tokenizer by lazy { WakeWordTokenizer.fromModel(context.assets.open("keyword/bpe.model")) }
    val phrase: String get() = prefs.getString("wake_word", DEFAULT) ?: DEFAULT

    fun save(value: String): String {
        val phrase = WakeWordTokenizer.normalize(value)
        tokenizer.encode(phrase)
        prefs.edit().putString("wake_word", phrase).apply()
        return phrase
    }

    fun tokens(phrase: String = this.phrase): String = tokenizer.encode(phrase)

    companion object { const val DEFAULT = "Jenkins" }
}
