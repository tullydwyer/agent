package dev.pixelagent.model

import org.junit.Assert.*
import org.junit.Test

class ResponseStreamTest {
    private fun read(value: String) = ResponseStream.read(value.reader().buffered())
    @Test fun acceptsMultilineCompletion() {
        val response = read(": heartbeat\n\ndata: {\"type\":\"response.completed\",\ndata: \"response\":{\"status\":\"completed\",\"output\":[]}}\n\ndata: [DONE]\n\n")
        assertEquals("completed", response.getString("status"))
    }
    @Test fun rejectsPartialDisconnect() {
        assertThrows(IllegalStateException::class.java) {
            read("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n")
        }
    }
    @Test fun rejectsUsageLimitAfterText() {
        val failure = assertThrows(IllegalStateException::class.java) {
            read("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\ndata: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n")
        }
        assertTrue(failure.message!!.contains("subscription_sharing_usage_limit_exceeded"))
    }
    @Test fun rejectsIncompleteAndFalseCompletion() {
        listOf("{\"type\":\"response.incomplete\"}", "{\"type\":\"response.completed\",\"response\":{\"status\":\"failed\"}}")
            .forEach { value -> assertThrows(IllegalStateException::class.java) { read("data: $value\n\n") } }
    }
    @Test fun retainsTextWhenTerminalEnvelopeOmitsOutput() {
        val result = read("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n")
        assertEquals("hello", result.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun retainsFinishedToolOnlyAfterCompletion() {
        val item = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"function_call\",\"name\":\"echo\"}}\n\n"
        assertThrows(IllegalStateException::class.java) { read(item) }
        val result = read(item + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n")
        assertEquals("echo", result.getJSONArray("output").getJSONObject(0).getString("name"))
    }
}
