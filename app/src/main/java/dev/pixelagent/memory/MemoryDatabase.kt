package dev.pixelagent.memory

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "memories")
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val provenance: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    val confirmed: Boolean = false,
)

// External-content FTS triggers are maintained by Room, including UPDATE/DELETE.
@Fts4(contentEntity = MemoryFact::class)
@Entity(tableName = "memory_search")
data class MemorySearch(val text: String)

@Entity(tableName = "persona")
data class Persona(@PrimaryKey val id: Int = 1, val text: String, val updatedAt: Long)

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC, id DESC LIMIT 200")
    fun observe(): Flow<List<MemoryFact>>
    @Query("SELECT * FROM memories ORDER BY id")
    suspend fun all(): List<MemoryFact>
    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int
    @Insert suspend fun insert(fact: MemoryFact): Long
    @Query("UPDATE memories SET text=:text, provenance=:provenance, updatedAt=:now, expiresAt=:expiresAt, confirmed=:confirmed WHERE id=:id")
    suspend fun edit(id: Long, text: String, provenance: String, now: Long, expiresAt: Long?, confirmed: Boolean)
    @Query("DELETE FROM memories WHERE id=:id") suspend fun forget(id: Long)
    @Query("DELETE FROM memories") suspend fun deleteAll()
    @Query("DELETE FROM memories WHERE expiresAt IS NOT NULL AND expiresAt <= :now") suspend fun expire(now: Long): Int
    @Query("SELECT EXISTS(SELECT 1 FROM memories WHERE expiresAt IS NOT NULL AND expiresAt <= :now)") suspend fun hasExpired(now: Long): Boolean
    @Query("SELECT m.* FROM memories m JOIN memory_search s ON m.id=s.rowid WHERE memory_search MATCH :query AND m.confirmed=1 AND (m.expiresAt IS NULL OR m.expiresAt > :now) ORDER BY m.updatedAt DESC, m.id DESC LIMIT 8")
    suspend fun retrieve(query: String, now: Long): List<MemoryFact>
    @Query("SELECT * FROM persona WHERE id=1") suspend fun persona(): Persona?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun savePersona(persona: Persona)
    @Query("DELETE FROM persona") suspend fun deletePersona()
}

@Database(entities = [MemoryFact::class, MemorySearch::class, Persona::class], version = 1, exportSchema = true)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memories(): MemoryDao
}
