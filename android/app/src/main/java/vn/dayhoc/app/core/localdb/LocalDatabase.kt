package vn.dayhoc.app.core.localdb

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Chỉ lưu cột tối thiểu (mã buổi, trạng thái, ghi chú): không SĐT, không ngày sinh. Token không bao giờ vào đây. */
@Entity(tableName = "outbox_batches")
data class OutboxBatchEntity(
    @PrimaryKey val idempotencyKey: String,
    val sessionId: String,
    val mutationsJson: String,
    val markSessionTaught: Boolean,
    val createdAt: Long,
    val attempts: Int,
)

@Entity(tableName = "roster_cache")
data class RosterEntity(@PrimaryKey val sessionId: String, val json: String, val cachedAt: Long)

@Dao
interface LocalDao {
    @Query("SELECT * FROM outbox_batches ORDER BY createdAt, idempotencyKey")
    fun observeBatches(): Flow<List<OutboxBatchEntity>>

    @Query("SELECT * FROM outbox_batches ORDER BY createdAt, idempotencyKey")
    suspend fun batches(): List<OutboxBatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: OutboxBatchEntity)

    @Query("DELETE FROM outbox_batches WHERE idempotencyKey = :key")
    suspend fun deleteBatch(key: String)

    @Query("UPDATE outbox_batches SET attempts = attempts + 1 WHERE idempotencyKey = :key")
    suspend fun addAttempt(key: String)

    @Query("SELECT json FROM roster_cache WHERE sessionId = :sessionId")
    suspend fun roster(sessionId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoster(roster: RosterEntity)

    @Query("DELETE FROM roster_cache WHERE cachedAt < :before")
    suspend fun deleteRosterBefore(before: Long)
}

@Database(entities = [OutboxBatchEntity::class, RosterEntity::class], version = 1, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
}
