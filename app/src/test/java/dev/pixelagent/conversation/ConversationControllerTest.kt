package dev.pixelagent.conversation

import dev.pixelagent.skills.FixtureSource
import dev.pixelagent.skills.SkillRegistry
import dev.pixelagent.skills.SkillSession
import dev.pixelagent.tools.ToolCall
import dev.pixelagent.tools.ToolDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationControllerTest {
    private val registry = SkillRegistry(FixtureSource())
    private val session = SkillSession(registry)
    private var allowed = true
    private val dispatcher = ToolDispatcher(session) { allowed }
    private fun function(id: String, name: String = "activate", args: String = "{\"name\":\"sample\"}", namespace: String = "skills") =
        JSONObject().put("type", "function_call").put("call_id", id).put("namespace", namespace).put("name", name).put("arguments", args)
    private fun response(vararg output: JSONObject) = JSONObject().put("status", "completed").put("output", JSONArray(output))
    private fun message(text: String = "Done") = JSONObject().put("type", "message").put("role", "assistant")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))
    private fun model(block: suspend (JSONArray, (String) -> Unit) -> JSONObject) = object : ConversationModel {
        override suspend fun complete(input: JSONArray, tools: JSONArray, onDelta: (String) -> Unit) = block(input, onDelta)
    }
    private suspend inline fun <reified T : Throwable> fails(noinline block: suspend () -> Unit) {
        try { block(); fail("Expected ${T::class.java.simpleName}") } catch (error: Throwable) { if (error !is T) throw error }
    }
    @Test fun realConversationProtocolLoadsInstructionsAndResourceThenReplies() = runTest {
        var requests = 0
        val provider = model { input, _ ->
            when (requests++) {
                0 -> response(function("one"))
                1 -> {
                    assertTrue(input.toString().contains("Read references/note.md"))
                    response(function("two", "read_resource", "{\"name\":\"sample\",\"relative_path\":\"references/note.md\"}"))
                }
                else -> { assertTrue(input.toString().contains("Test resource marker")); response(message()) }
            }
        }
        assertEquals("Done", ConversationController(provider, dispatcher).run(JSONArray(), {}))
        assertEquals(3, requests)
        assertEquals(listOf("sample"), session.names)
    }
    @Test fun permissionDenialAndUnknownToolsNeverActivate() = runTest {
        allowed = false
        assertEquals("permission_denied", JSONObject(dispatcher.execute(ToolCall("one", "skills", "activate", "{\"name\":\"sample\"}"))).getString("error"))
        assertTrue(session.names.isEmpty())
        allowed = true
        assertEquals("unknown_tool", JSONObject(dispatcher.execute(ToolCall("two", "phone", "send", "{}"))).getString("error"))
    }
    @Test fun validatesArgumentsIncludingExtraFieldsAndTrailingJson() = runTest {
        listOf("{}", "[]", "{\"name\":12}", "{\"name\":\"sample\",\"grant\":true}", "{\"name\":\"sample\"} {}", "x".repeat(2049)).forEach {
            assertFalse(JSONObject(dispatcher.execute(ToolCall("one", "skills", "activate", it))).getBoolean("ok"))
        }
        assertTrue(session.names.isEmpty())
    }
    @Test fun duplicateCallsRejectedBeforeExecutingBatch() = runTest {
        val provider = model { _, _ -> response(function("same"), function("same")) }
        fails<IllegalArgumentException> { ConversationController(provider, dispatcher).run(JSONArray(), {}) }
        assertTrue(session.names.isEmpty())
    }
    @Test fun toolRoundLimitStopsModelWithoutReplayingCalls() = runTest {
        var requests = 0
        var executed = 0
        val provider = model { _, _ -> response(function("call-${requests++}")) }
        fails<IllegalStateException> { ConversationController(provider, dispatcher).run(JSONArray(), {}, { _, _ -> executed++ }) }
        assertEquals(6, requests)
        assertEquals(5, executed)
    }
    @Test fun tooManyCallsRejectsWholeBatch() = runTest {
        val provider = model { _, _ -> response(*(1..5).map { function("call-$it") }.toTypedArray()) }
        fails<IllegalStateException> { ConversationController(provider, dispatcher).run(JSONArray(), {}) }
        assertTrue(session.names.isEmpty())
    }
    @Test fun interruptionAfterTextDoesNotExecuteToolsOrRetry() = runTest {
        var requests = 0
        val deltas = mutableListOf<String>()
        val provider = model { _, onDelta -> requests++; onDelta("Partial reply."); throw java.io.IOException("disconnect") }
        fails<java.io.IOException> { ConversationController(provider, dispatcher).run(JSONArray(), { deltas.add(it) }) }
        assertEquals(listOf("Partial reply."), deltas)
        assertEquals(1, requests)
        assertTrue(session.names.isEmpty())
    }
    @Test fun failedTerminalResponseDoesNotAuthorizeTool() = runTest {
        val provider = model { _, _ -> response(function("one")).put("status", "failed") }
        fails<IllegalStateException> { ConversationController(provider, dispatcher).run(JSONArray(), {}) }
        assertTrue(session.names.isEmpty())
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellationStopsOutstandingTurnAndTimeoutIsBounded() = runTest {
        var cancelled = 0
        val provider = model { _, _ -> try { awaitCancellation() } finally { cancelled++ } }
        val job = launch { ConversationController(provider, dispatcher).run(JSONArray(), {}) }
        runCurrent(); job.cancel(); job.join()
        assertEquals(1, cancelled)
        fails<CancellationException> { ConversationController(provider, dispatcher, 100).run(JSONArray(), {}) }
        assertEquals(2, cancelled)
        assertTrue(session.names.isEmpty())
    }
    @Test fun rebuildIncludesActiveResourcesButNoExecutableTranscript() {
        session.activate("sample"); session.read("sample", "references/note.md")
        val saved = session.save()
        val input = ConversationContext.build("Concise", listOf(ChatMessage("user", "Old question"), ChatMessage("assistant", "Old answer")), "New question", registry, SkillSession(registry, saved), true)
        assertTrue(input.toString().contains("Test resource marker"))
        assertTrue(input.toString().contains("Old answer"))
        assertFalse(input.toString().contains("function_call"))
        val disabled = ConversationContext.build("Concise", emptyList(), "New", registry, session, false)
        assertFalse(disabled.toString().contains("Test resource marker"))
    }
}
