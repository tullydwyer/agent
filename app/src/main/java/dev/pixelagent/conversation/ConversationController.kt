package dev.pixelagent.conversation

import dev.pixelagent.tools.ToolCall
import dev.pixelagent.tools.ToolDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

interface ConversationModel {
    suspend fun complete(input: JSONArray, tools: JSONArray, onDelta: (String) -> Unit): JSONObject
}

/** One cancellable turn, at most five tool rounds and twelve sequential calls.
 * Automatic retries are deliberately bounded to zero: an interrupted request may
 * have consumed plan allowance. A user may explicitly start a new turn. */
class ConversationController(private val model: ConversationModel, private val dispatcher: ToolDispatcher, private val timeoutMs: Long = 90_000) {
    suspend fun run(input: JSONArray, onDelta: (String) -> Unit, onTool: (ToolCall, String) -> Unit = { _, _ -> }): String = withTimeout(timeoutMs) {
        val history = JSONArray(input.toString())
        val seen = mutableSetOf<String>()
        var totalCalls = 0
        for (round in 0..5) {
            currentCoroutineContext().ensureActive()
            check(history.toString().length <= 384_000) { "turn_context_limit" }
            val response = model.complete(history, dispatcher.definitions, onDelta)
            currentCoroutineContext().ensureActive()
            check(response.optString("status") == "completed" && response.isNull("error")) { "response_not_completed" }
            val output = response.getJSONArray("output")
            val calls = mutableListOf<ToolCall>()
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") == "function_call") {
                    val id = item.getString("call_id")
                    require(id.length in 1..200 && seen.add(id)) { "duplicate_or_invalid_call" }
                    calls.add(ToolCall(id, item.getString("namespace"), item.getString("name"), item.getString("arguments")))
                }
            }
            // Validate the entire batch/budget before executing any local capability.
            check(calls.size <= 4 && totalCalls + calls.size <= 12) { "tool_call_limit" }
            if (calls.isEmpty()) return@withTimeout responseText(output).ifBlank { error("empty_response") }
            check(round < 5) { "tool_round_limit" }
            for (i in 0 until output.length()) history.put(output.getJSONObject(i))
            for (call in calls) {
                currentCoroutineContext().ensureActive()
                val result = dispatcher.execute(call)
                currentCoroutineContext().ensureActive()
                history.put(JSONObject().put("type", "function_call_output").put("call_id", call.id).put("output", result))
                totalCalls++
                onTool(call, result)
            }
        }
        error("tool_round_limit")
    }

    companion object {
        fun responseText(output: JSONArray): String = buildString {
            for (i in 0 until output.length()) {
                val item = output.getJSONObject(i)
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val part = content.getJSONObject(j)
                    if (part.optString("type") == "output_text") append(part.getString("text"))
                }
            }
        }.also { check(it.length <= 16_000) { "reply_size_limit" } }
    }
}
