package dev.pixelagent.wakeword

import org.junit.Assert.*
import org.junit.Test

class WakeWordTokenizerTest {
    @Test fun unigramChoosesTheBestCompletePathInsteadOfLongestMatchingPrefix() {
        val tokenizer = WakeWordTokenizer(mapOf("▁" to -10f, "A" to -10f, "B" to -10f,
            "C" to -10f, "▁A" to -5f, "AB" to -3f, "BC" to -1f))
        assertEquals("▁A BC", tokenizer.encode("ABC"))
    }

    @Test fun normalizationAndValidationKeepKeywordSyntaxOutOfUserInput() {
        assertEquals("Hey Jenkins", WakeWordTokenizer.normalize("  Hey   Jenkins  "))
        for (invalid in listOf("", "A", "123", "你好", "Jenkins @other", "Jenkins:5", "one two three four five", "A".repeat(61))) {
            assertThrows(IllegalArgumentException::class.java) { WakeWordTokenizer.normalize(invalid) }
        }
        assertEquals("What's up", WakeWordTokenizer.normalize("What's up"))
    }
}
