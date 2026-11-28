package vn.dayhoc.app.core.localdb

import android.content.Context
import android.util.Base64
import androidx.room.Room
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import vn.dayhoc.app.teacher.attendance.DeviceIdProvider
import vn.dayhoc.app.teacher.attendance.OutboxBatch
import vn.dayhoc.app.teacher.attendance.OutboxMutation
import vn.dayhoc.app.teacher.attendance.OutboxStore
import vn.dayhoc.app.teacher.attendance.RosterCache

/**
 * CSDL cục bộ Room mã hóa toàn file bằng SQLCipher (Technical Spec 6.4 mục 7). Khóa 32 byte ngẫu nhiên sinh lần đầu,
 * bọc bằng Keystore rồi lưu trong SharedPreferences riêng; không mở được CSDL chưa mã hóa.
 * [wipe] xóa CSDL và khóa khi đăng xuất (mã thiết bị giữ theo lần cài đặt).
 */
class LocalStore(context: Context) : OutboxStore, RosterCache, DeviceIdProvider {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)
    private val lock = Mutex()
    private var db: LocalDatabase? = null
    private val json = Json

    private suspend fun dao(): LocalDao = lock.withLock {
        withContext(Dispatchers.IO) { (db ?: open().also { db = it }).dao() }
    }

    private fun open(): LocalDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(app, LocalDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(passphrase()))
            .build()
    }

    /** Khóa không giải mã được (Keystore bị reset): dữ liệu cũ không còn đọc được, bỏ và tạo CSDL mới. */
    private fun passphrase(): ByteArray {
        prefs.getString(KEY_BLOB, null)?.let { blob ->
            try {
                return cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP))
            } catch (e: Exception) {
                app.deleteDatabase(DB_NAME)
            }
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(cipher.encrypt(fresh), Base64.NO_WRAP)).apply()
        return fresh
    }

    override val batches: Flow<List<OutboxBatch>> =
        flow { emitAll(dao().observeBatches().map { list -> list.map { it.toDomain() } }) }.flowOn(Dispatchers.IO)

    override suspend fun enqueue(batch: OutboxBatch) = dao().insertBatch(batch.toEntity())
    override suspend fun all(): List<OutboxBatch> = dao().batches().map { it.toDomain() }
    override suspend fun remove(idempotencyKey: String) = dao().deleteBatch(idempotencyKey)
    override suspend fun addAttempt(idempotencyKey: String) = dao().addAttempt(idempotencyKey)

    override suspend fun get(sessionId: String): String? = dao().roster(sessionId)

    override suspend fun put(sessionId: String, json: String) {
        val now = System.currentTimeMillis()
        dao().putRoster(RosterEntity(sessionId, json, now))
        dao().deleteRosterBefore(now - ROSTER_TTL_MS)
    }

    override suspend fun get(): String = prefs.getString(DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).apply() }

    /** Đăng xuất: xóa CSDL và khóa. Gọi sau khi đã chặn/hủy việc đồng bộ nền. */
    suspend fun wipe() = lock.withLock {
        withContext(Dispatchers.IO) {
            db?.close()
            db = null
            app.deleteDatabase(DB_NAME)
            prefs.edit().remove(KEY_BLOB).apply()
            try {
                cipher.deleteKey()
            } catch (e: Exception) {
                // Khóa đã mất hoặc Keystore lỗi: khóa bọc không còn nên CSDL cũng không đọc được nữa.
            }
        }
    }

    private fun OutboxBatchEntity.toDomain() = OutboxBatch(
        idempotencyKey, sessionId, json.decodeFromString(ListSerializer(OutboxMutation.serializer()), mutationsJson),
        markSessionTaught, createdAt, attempts,
    )

    private fun OutboxBatch.toEntity() = OutboxBatchEntity(
        idempotencyKey, sessionId, json.encodeToString(ListSerializer(OutboxMutation.serializer()), mutations),
        markSessionTaught, createdAt, attempts,
    )

    private companion object {
        const val PREFS = "dayhoc_local_db"
        const val KEY_BLOB = "db_key"
        const val DEVICE_ID = "device_id"
        const val KEY_ALIAS = "dayhoc_local_db"
        const val DB_NAME = "dayhoc_local.db"
        const val ROSTER_TTL_MS = 14L * 24 * 60 * 60 * 1000
    }
}
