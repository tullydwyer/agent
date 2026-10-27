package dev.pixelagent.model

import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader

/** Collect only a completed response. Partial output never authorizes a local tool. */
object ResponseStream {
    fun read(reader: BufferedReader): JSONObject {
        val data = StringBuilder()
        var completed: JSONObject? = null
        val finishedItems = sortedMapOf<Int, JSONObject>()
        val textParts = sortedMapOf<Pair<Int, Int>, StringBuilder>(compareBy<Pair<Int, Int>> { it.first }.thenBy { it.second })
        var total = 0
        fun dispatch() {
            if (data.isEmpty()) return
            val text = data.toString().trimEnd('\n')
            data.setLength(0)
            if (text == "[DONE]") return
            val event = JSONObject(text)
            when (event.optString("type")) {
                "response.output_item.done" -> finishedItems[event.getInt("output_index")] = event.getJSONObject("item")
                "response.output_text.delta" -> textParts.getOrPut(event.optInt("output_index") to event.optInt("content_index")) { StringBuilder() }
                    .append(event.getString("delta"))
                "response.output_text.done" -> {
                    val key = event.optInt("output_index") to event.optInt("content_index")
                    if (textParts[key].isNullOrEmpty()) textParts[key] = StringBuilder(event.getString("text"))
                }
                "response.completed" -> {
                    val response = event.getJSONObject("response")
                    check(response.optString("status") == "completed" && response.isNull("error")) { "Response completion rejected." }
                    completed = response
                }
                "response.failed", "response.incomplete", "error" -> {
                    val code = event.optJSONObject("response")?.optJSONObject("error")?.optString("code")
                        ?: event.optJSONObject("error")?.optString("code") ?: event.optString("code", "incomplete")
                    // Do not surface provider messages containing prompts or personal content.
                    error("Model request failed: ${code.replace(Regex("[^a-zA-Z0-9_]"), "").take(100)}. No action executed.")
                }
            }
        }
        while (true) {
            val line = reader.readLine() ?: break
            total += line.length
            check(total <= 4_000_000) { "Response exceeded the experiment size limit." }
            if (line.isEmpty()) dispatch()
            else if (line.startsWith("data:")) data.append(line.substring(5).removePrefix(" ")).append('\n')
        }
        dispatch()
        val response = completed ?: error("Stream disconnected before response.completed. No action executed.")
        var output = response.optJSONArray("output") ?: JSONArray()
        // Some plan-route streams omit output from the terminal envelope; completed
        // item events still contain the final message/tool arguments.
        if (output.length() == 0 && finishedItems.isNotEmpty()) output = JSONArray(finishedItems.values.toList())
        val hasText = (0 until output.length()).any { index ->
            val content = output.getJSONObject(index).optJSONArray("content")
            content != null && (0 until content.length()).any { content.getJSONObject(it).optString("type") == "output_text" }
        }
        if (!hasText && textParts.isNotEmpty()) {
            output.put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", textParts.values.joinToString("") { it.toString() }))))
        }
        return response.put("output", output)
    }
}
