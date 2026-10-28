package dev.pixelagent.conversation

import dev.pixelagent.memory.MemoryRetrieval
import dev.pixelagent.memory.MemoryFact
import dev.pixelagent.skills.SkillRegistry
import dev.pixelagent.skills.SkillSession
import dev.pixelagent.skills.SkillSource
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class MemoryContextTest {
    private val registry = SkillRegistry(object : SkillSource {
        override fun directories() = emptyList<String>()
        override fun files(name: String) = emptyList<String>()
        override fun read(name: String, path: String, maxBytes: Int) = error("No skills")
    })
    @Test fun searchSyntaxIsLiteralAndBounded() {
        assertEquals("\"hello\" OR \"or\" OR \"near\" OR \"world\"", MemoryRetrieval.query("hello\" OR NEAR(world) hello *"))
        assertNull(MemoryRetrieval.query("! * ?"))
        assertEquals(16, MemoryRetrieval.query((10..99).joinToString(" "))!!.split(" OR ").size)
    }
    @Test fun snapshotIsAttachedOnlyToCurrentMessageAndHistoryHasNoBytes() {
        val url = "data:image/jpeg;base64,abcd"
        val input = ConversationContext.build("Be kind", listOf(ChatMessage("user", "old question"), ChatMessage("assistant", "old answer")),
            "Read the label", registry, SkillSession(registry), false, "[]", url)
        val current = input.getJSONObject(input.length() - 1).getJSONArray("content")
        assertEquals("Read the label", current.getJSONObject(0).getString("text"))
        assertEquals(url, current.getJSONObject(1).getString("image_url"))
        val next = ConversationContext.build("Be kind", emptyList(), "next", registry, SkillSession(registry), false)
        assertFalse(next.toString().contains("input_image"))
        assertFalse(next.toString().contains(url))
    }
    @Test fun escapedMemoryIsBoundedWithoutBreakingFacts() {
        val facts = List(8) { MemoryFact(text = "\u0001".repeat(1000), provenance = "\u0001".repeat(300), createdAt = 0, updatedAt = 0, confirmed = true) }
        val context = MemoryRetrieval.context(facts)
        assertTrue(context.length <= 16000)
        assertEquals(2, JSONArray(context).length())
        assertEquals(facts[0].text, JSONArray(context).getJSONObject(0).getString("fact"))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedImageRejected() {
        ConversationContext.build("", emptyList(), "image", registry, SkillSession(registry), false, "[]", "data:image/jpeg;base64," + "a".repeat(250000))
    }
}
