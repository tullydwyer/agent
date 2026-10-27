package dev.pixelagent.conversation

import org.junit.Assert.*
import org.junit.Test

class ConversationHistoryTest {
    @Test fun dropsToolAndIncompletePairsWithoutRestoringActions() {
        val saved = ConversationHistory.prepare(listOf(ChatMessage("user", "Question"), ChatMessage("assistant", "Answer"),
            ChatMessage("function_call", "send mail"), ChatMessage("tool", "pending"), ChatMessage("user", "Incomplete")))
        assertEquals(2, saved.messages.size)
        assertEquals(saved.messages, ConversationHistory.restore(saved.json))
        assertFalse(saved.json.contains("send mail"))
    }
    @Test fun jsonEscapingIsBoundedAndRetainsWholeLatestExchanges() {
        val messages = (1..20).flatMap { listOf(ChatMessage("user", "$it:" + "\u0001".repeat(3996)), ChatMessage("assistant", "$it:" + "\u0001".repeat(15996))) }
        val saved = ConversationHistory.prepare(messages)
        assertTrue(saved.json.length <= 450_000)
        assertTrue(saved.messages.size < 40)
        assertEquals(0, saved.messages.size % 2)
        assertEquals(messages.takeLast(2), saved.messages.takeLast(2))
        assertEquals(saved.messages, ConversationHistory.restore(saved.json))
    }
    @Test fun restoredDataCannotExceedStorageOrMessageLimits() {
        assertThrows(Exception::class.java) { ConversationHistory.restore("x".repeat(450001)) }
        val saved = ConversationHistory.prepare((1..30).flatMap { listOf(ChatMessage("user", "$it"), ChatMessage("assistant", "$it")) })
        assertEquals(40, saved.messages.size)
        assertEquals("11", saved.messages.first().text)
    }
}
