package vn.dayhoc.app.core.localdb

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Chỉ lưu cột tối thiểu (mã buổi, trạng thái, ghi chú): không SĐT, không ngày sinh. Token không bao giờ vào đây. */
@Entity(tableName = "outbox_batches")
data class OutboxBatchEntity(
    @PrimaryKey val idempotencyKey: String,
    val sessionId: String,
    @ColumnInfo(defaultValue = "''") val ownerUserId: String,
    @ColumnInfo(defaultValue = "''") val tenantId: String,
    /** Body đã dựng lần đầu (gồm `deviceId`, `deviceTime`) lưu nguyên: backend băm cả body vào khóa idempotency. */
    @ColumnInfo(defaultValue = "''") val deviceId: String,
    @ColumnInfo(defaultValue = "''") val deviceTime: String,
    val mutationsJson: String,
    val markSessionTaught: Boolean,
    val createdAt: Long,
    val attempts: Int,
    /** `pending` | `failed`. */
    @ColumnInfo(defaultValue = "'pending'") val state: String,
    val errorCode: String?,
)

@Entity(tableName = "roster_cache", primaryKeys = ["sessionId", "ownerUserId", "tenantId"])
data class RosterEntity(
    val sessionId: String,
    val ownerUserId: String,
    val tenantId: String,
    val json: String,
    val cachedAt: Long,
)

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

    @Query("UPDATE outbox_batches SET state = 'failed', errorCode = :code WHERE idempotencyKey = :key")
    suspend fun markFailed(key: String, code: String)

    @Query("UPDATE outbox_batches SET state = 'pending', errorCode = NULL, attempts = 0 WHERE sessionId = :sessionId AND state = 'failed'")
    suspend fun retryFailed(sessionId: String)

    @Query("SELECT json FROM roster_cache WHERE sessionId = :sessionId AND ownerUserId = :userId AND tenantId = :tenantId")
    suspend fun roster(sessionId: String, userId: String, tenantId: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoster(roster: RosterEntity)

    @Query("DELETE FROM roster_cache WHERE sessionId = :sessionId")
    suspend fun deleteRoster(sessionId: String)

    @Query("DELETE FROM roster_cache WHERE cachedAt < :before")
    suspend fun deleteRosterBefore(before: Long)

    @Query("DELETE FROM roster_cache WHERE ownerUserId != :userId OR tenantId != :tenantId")
    suspend fun deleteForeignRosters(userId: String, tenantId: String)
}

@Database(entities = [OutboxBatchEntity::class, RosterEntity::class], version = 2, exportSchema = true)
abstract class LocalDatabase : RoomDatabase() {
    abstract fun dao(): LocalDao
}

/**
 * 1 → 2: thêm chủ (người dùng, tenant), body đã dựng (`deviceId`, `deviceTime`) và trạng thái lô; bản chụp danh sách
 * đổi khóa chính theo chủ. Dòng cũ không có chủ nên không bao giờ được gửi và bị dọn ở lần đăng nhập kế tiếp; không xóa gì ở đây.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN ownerUserId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN tenantId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN deviceId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN deviceTime TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'")
        db.execSQL("ALTER TABLE outbox_batches ADD COLUMN errorCode TEXT")
        db.execSQL("ALTER TABLE roster_cache RENAME TO roster_cache_old")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `roster_cache` (`sessionId` TEXT NOT NULL, `ownerUserId` TEXT NOT NULL, " +
                "`tenantId` TEXT NOT NULL, `json` TEXT NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sessionId`, `ownerUserId`, `tenantId`))",
        )
        db.execSQL("INSERT INTO roster_cache (sessionId, ownerUserId, tenantId, json, cachedAt) SELECT sessionId, '', '', json, cachedAt FROM roster_cache_old")
        db.execSQL("DROP TABLE roster_cache_old")
    }
}
