package dev.pixelagent.wakeword

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** SentencePiece unigram encoding for the bundled English model and a bounded ASCII phrase. */
class WakeWordTokenizer(private val scores: Map<String, Float>) {
    fun encode(phrase: String): String {
        val normalized = normalize(phrase).uppercase(Locale.ROOT)
        val text = "▁" + normalized.replace(' ', '▁')
        val best = FloatArray(text.length + 1) { Float.NEGATIVE_INFINITY }
        val previous = IntArray(text.length + 1) { -1 }
        best[0] = 0f
        for (start in text.indices) {
            if (best[start] == Float.NEGATIVE_INFINITY) continue
            for ((piece, score) in scores) {
                if (!text.startsWith(piece, start)) continue
                val end = start + piece.length
                val candidate = best[start] + score
                if (candidate > best[end]) { best[end] = candidate; previous[end] = start }
            }
        }
        require(previous[text.length] >= 0) { "This phrase cannot be encoded by the English wake engine." }
        val pieces = mutableListOf<String>()
        var end = text.length
        while (end > 0) {
            val start = previous[end]
            pieces += text.substring(start, end)
            end = start
        }
        return pieces.asReversed().joinToString(" ")
    }

    companion object {
        fun normalize(value: String): String {
            val phrase = value.trim().replace(Regex("\\s+"), " ")
            require(phrase.length in 2..60 && phrase.split(' ').size <= 4 &&
                Regex("[A-Za-z]+(?:'[A-Za-z]+)*(?: [A-Za-z]+(?:'[A-Za-z]+)*)*").matches(phrase)) {
                "Use 1–4 English words, 2–60 letters and spaces. Apostrophes are allowed."
            }
            return phrase
        }

        fun fromModel(input: InputStream): WakeWordTokenizer {
            val scores = mutableMapOf<String, Float>()
            val model = fields(input.use { it.readBytes() })
            val trainer = fields(model.first { it.first == 2 }.second).toMap()
            check(trainer[3]?.firstOrNull()?.toInt() == 1) { "Expected the bundled unigram wake-word vocabulary." }
            // Read only piece text, score and type from the pinned SentencePiece ModelProto.
            for ((field, bytes) in model) {
                if (field != 1) continue
                val piece = fields(bytes).toMap()
                if (piece[3]?.firstOrNull()?.toInt()?.let { it != 1 } == true) continue
                val text = piece.getValue(1).toString(Charsets.UTF_8)
                scores[text] = ByteBuffer.wrap(piece.getValue(2)).order(ByteOrder.LITTLE_ENDIAN).float
            }
            check(scores.isNotEmpty()) { "Wake-word vocabulary is missing." }
            return WakeWordTokenizer(scores)
        }

        private fun fields(bytes: ByteArray): List<Pair<Int, ByteArray>> {
            var position = 0
            fun varint(): Long {
                var value = 0L
                var shift = 0
                while (shift < 70) {
                    val next = bytes[position++].toInt() and 255
                    value = value or ((next and 127).toLong() shl shift)
                    if (next < 128) return value
                    shift += 7
                }
                error("Invalid wake-word model.")
            }
            val result = mutableListOf<Pair<Int, ByteArray>>()
            while (position < bytes.size) {
                val tag = varint().toInt()
                val value = when (tag and 7) {
                    0 -> byteArrayOf(varint().toByte())
                    2 -> { val size = varint().toInt(); bytes.copyOfRange(position, position + size).also { position += size } }
                    5 -> bytes.copyOfRange(position, position + 4).also { position += 4 }
                    1 -> bytes.copyOfRange(position, position + 8).also { position += 8 }
                    else -> error("Invalid wake-word model field.")
                }
                result += (tag ushr 3) to value
            }
            return result
        }
    }
}
