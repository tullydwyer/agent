package dev.pixelagent.memory

import org.json.JSONArray
import org.json.JSONObject
import androidx.room.withTransaction

/** Local user-confirmed facts. No embeddings, hidden summaries, or model writes. */
class MemoryRepository(private val db: MemoryDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.memories()
    val facts = dao.observe()
    suspend fun initializePersona(legacy: String): String {
        val saved = dao.persona()
        if (saved != null) return saved.text
        setPersona(legacy)
        return legacy.take(1000)
    }
    suspend fun setPersona(text: String) = dao.savePersona(Persona(text = text.take(1000), updatedAt = clock()))
    suspend fun save(id: Long?, text: String, provenance: String, expiresAt: Long?, confirmed: Boolean) {
        val value = text.trim()
        val source = provenance.trim()
        require(value.length in 1..1000 && source.length in 1..300) { "Enter a fact and its source (up to 1000/300 characters)." }
        require(expiresAt == null || expiresAt > clock()) { "Expiry must be in the future." }
        if (id == null) {
            require(dao.count() < 200) { "Memory is full (200 facts). Forget or export facts first." }
            dao.insert(MemoryFact(text = value, provenance = source, createdAt = clock(), updatedAt = clock(), expiresAt = expiresAt, confirmed = confirmed))
        } else dao.edit(id, value, source, clock(), expiresAt, confirmed)
    }
    suspend fun forget(id: Long) = dao.forget(id)
    suspend fun purgeExpired(): Int = dao.expire(clock())
    suspend fun hasExpired(): Boolean = dao.hasExpired(clock())
    suspend fun deleteAll() = db.withTransaction { dao.deleteAll(); dao.deletePersona() }
    suspend fun retrieve(message: String): List<MemoryFact> {
        val query = MemoryRetrieval.query(message) ?: return emptyList()
        return dao.retrieve(query, clock()).take(8)
    }
    suspend fun export(): String {
        return JSONObject().put("version", 1).put("exportedAt", clock())
            .put("persona", dao.persona()?.text ?: "")
            .put("memories", JSONArray(dao.all().map { fact -> JSONObject()
                .put("id", fact.id).put("text", fact.text).put("provenance", fact.provenance)
                .put("createdAt", fact.createdAt).put("updatedAt", fact.updatedAt)
                .put("expiresAt", fact.expiresAt ?: JSONObject.NULL).put("confirmed", fact.confirmed) })).toString(2)
    }
}

object MemoryRetrieval {
    // Quote each token; never interpret a user's FTS operators or column syntax.
    fun query(text: String): String? = Regex("[\\p{L}\\p{N}]{2,40}").findAll(text.take(4000))
        .map { it.value.lowercase() }.distinct().take(16).toList()
        .takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"$it\"" }
    fun context(facts: List<MemoryFact>): String {
        var result = JSONArray()
        for (fact in facts.take(8)) {
            val next = JSONArray(result.toString()).put(JSONObject()
                .put("fact", fact.text.take(1000)).put("source", fact.provenance.take(300)))
            if (next.toString().length > 16000) break
            result = next
        }
        return result.toString()
    }
}
