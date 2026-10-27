package dev.pixelagent.conversation

import org.json.JSONArray
import org.json.JSONObject

/** Only completed user/assistant pairs cross the persistence boundary. */
object ConversationHistory {
    data class Saved(val messages: List<ChatMessage>, val json: String)
    fun restore(raw: String): List<ChatMessage> {
        require(raw.length <= 450_000)
        val array = JSONArray(raw)
        require(array.length() <= 40)
        return (0 until array.length()).map { array.getJSONObject(it) }
            .map { ChatMessage(it.getString("role"), it.getString("text")) }
            .chunked(2).filter(::validPair).flatten()
    }
    fun prepare(messages: List<ChatMessage>): Saved {
        val bounded = messages.chunked(2).filter(::validPair).flatten().takeLast(40).toMutableList()
        fun encode() = JSONArray(bounded.map { JSONObject().put("role", it.role).put("text", it.text) }).toString()
        var encoded = encode()
        // Escaping can expand characters sixfold. Drop whole oldest pairs only.
        while (encoded.length > 450_000 && bounded.size >= 2) {
            bounded.subList(0, 2).clear()
            encoded = encode()
        }
        return Saved(bounded.toList(), encoded)
    }
    private fun validPair(pair: List<ChatMessage>) = pair.size == 2 && pair[0].role == "user" && pair[1].role == "assistant" &&
        pair[0].text.length <= 4000 && pair[1].text.length <= 16000
}
