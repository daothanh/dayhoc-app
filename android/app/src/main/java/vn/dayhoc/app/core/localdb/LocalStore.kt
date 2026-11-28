package vn.dayhoc.app.core.localdb

import android.content.Context
import android.util.Base64
import androidx.room.Room
import java.security.InvalidKeyException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
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
import vn.dayhoc.app.teacher.attendance.OutboxOwner
import vn.dayhoc.app.teacher.attendance.OutboxState
import vn.dayhoc.app.teacher.attendance.OutboxStore
import vn.dayhoc.app.teacher.attendance.RosterCache

/**
 * CSDL cục bộ Room mã hóa toàn file bằng SQLCipher (Technical Spec 6.4 mục 7). Khóa 32 byte ngẫu nhiên sinh lần đầu,
 * bọc bằng Keystore rồi lưu trong SharedPreferences riêng; không mở được CSDL chưa mã hóa.
 * [wipe] xóa CSDL, khóa và chủ khi đăng xuất (mã thiết bị giữ theo lần cài đặt).
 * Chủ dữ liệu ([owner]) lưu bền để tác vụ nền (app chưa mở) biết lô nào được gửi.
 */
class LocalStore(context: Context) : OutboxStore, RosterCache, DeviceIdProvider {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)
    private val lock = Mutex()
    private var db: LocalDatabase? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Tăng sau mỗi [wipe] để các Flow đang theo dõi mở lại trên CSDL mới thay vì chết cùng CSDL cũ. */
    private val epoch = MutableStateFlow(0)

    private val _owner = MutableStateFlow(readOwner())
    val owner: StateFlow<OutboxOwner?> = _owner.asStateFlow()

    private suspend fun dao(): LocalDao = lock.withLock {
        withContext(Dispatchers.IO) { (db ?: open().also { db = it }).dao() }
    }

    private fun open(): LocalDatabase {
        System.loadLibrary("sqlcipher")
        return Room.databaseBuilder(app, LocalDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .openHelperFactory(SupportOpenHelperFactory(passphrase()))
            .build()
    }

    /**
     * Chỉ bỏ dữ liệu khi chắc chắn không giải mã được (khóa Keystore mất hoặc không hợp lệ: [AEADBadTagException],
     * [InvalidKeyException]); lỗi Keystore khác ném ra để lần mở sau thử lại, không xóa nhầm.
     */
    private fun passphrase(): ByteArray {
        val blob = prefs.getString(KEY_BLOB, null)
        if (blob != null) {
            try {
                return cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP))
            } catch (e: AEADBadTagException) {
                // khóa bọc đã đổi/mất
            } catch (e: InvalidKeyException) {
                // khóa bị vô hiệu hóa
            } catch (e: IllegalArgumentException) {
                // blob hỏng (Base64)
            }
        }
        // Khóa mới: CSDL cũ (nếu có) không còn giải mã được, xóa trước khi ghi khóa mới.
        app.deleteDatabase(DB_NAME)
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        check(prefs.edit().putString(KEY_BLOB, Base64.encodeToString(cipher.encrypt(fresh), Base64.NO_WRAP)).commit()) { "Không ghi được khóa CSDL" }
        return fresh
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val batches: Flow<List<OutboxBatch>> = epoch.flatMapLatest {
        flow { emitAll(dao().observeBatches().map { list -> list.map { it.toDomain() } }) }.flowOn(Dispatchers.IO)
    }

    override suspend fun enqueue(batch: OutboxBatch) = dao().insertBatch(batch.toEntity())
    override suspend fun all(): List<OutboxBatch> = dao().batches().map { it.toDomain() }
    override suspend fun remove(idempotencyKey: String) = dao().deleteBatch(idempotencyKey)
    override suspend fun addAttempt(idempotencyKey: String) = dao().addAttempt(idempotencyKey)
    override suspend fun markFailed(idempotencyKey: String, code: String) = dao().markFailed(idempotencyKey, code)
    override suspend fun retryFailed(sessionId: String) = dao().retryFailed(sessionId)

    override suspend fun get(sessionId: String, owner: OutboxOwner): String? = dao().roster(sessionId, owner.userId, owner.tenantId)

    override suspend fun put(sessionId: String, owner: OutboxOwner, json: String) {
        val now = System.currentTimeMillis()
        dao().putRoster(RosterEntity(sessionId, owner.userId, owner.tenantId, json, now))
        dao().deleteRosterBefore(now - ROSTER_TTL_MS)
    }

    override suspend fun removeRoster(sessionId: String) = dao().deleteRoster(sessionId)

    override suspend fun get(): String = prefs.getString(DEVICE_ID, null)
        ?: UUID.randomUUID().toString().also { prefs.edit().putString(DEVICE_ID, it).commit() }

    /** Mã người dùng từ phản hồi đăng nhập, lưu bền để gắn chủ cả khi mở app ngoại tuyến. */
    fun rememberUser(userId: String) {
        prefs.edit().putString(USER_ID, userId).commit()
    }

    fun userId(): String? = prefs.getString(USER_ID, null)

    /**
     * Gắn chủ (người dùng + tenant đang mở; gắn lại khi đổi tenant). Lô của chủ cũ giữ nguyên trong outbox, không gửi,
     * chờ chủ đó đăng nhập lại; chỉ bản chụp danh sách (có tên học sinh) của chủ khác bị xóa.
     */
    suspend fun adopt(owner: OutboxOwner) {
        prefs.edit().putString(OWNER, json.encodeToString(OutboxOwner.serializer(), owner)).commit()
        _owner.value = owner
        dao().deleteForeignRosters(owner.userId, owner.tenantId)
    }

    /** Hết phiên: bỏ gắn chủ (SyncEngine không gửi gì khi chưa có chủ), giữ nguyên lô để gửi sau khi đăng nhập lại. */
    fun unbindOwner() {
        prefs.edit().remove(OWNER).remove(USER_ID).commit()
        _owner.value = null
    }

    /** Số buổi còn điểm danh chưa đồng bộ (đang chờ hoặc lỗi) của chủ hiện tại, cho hộp thoại đăng xuất. */
    suspend fun unsyncedSessionCount(): Int {
        val me = _owner.value ?: return 0
        return all().filter { it.owner == me }.map { it.sessionId }.toSet().size
    }

    /** Đăng xuất: xóa CSDL, khóa và chủ. Gọi sau khi đã hủy việc đồng bộ nền. Hết phiên thì không gọi (giữ lô). */
    suspend fun wipe() {
        lock.withLock {
            withContext(Dispatchers.IO) {
                db?.close()
                db = null
                app.deleteDatabase(DB_NAME)
                prefs.edit().remove(KEY_BLOB).remove(OWNER).remove(USER_ID).commit()
                try {
                    cipher.deleteKey()
                } catch (e: Exception) {
                    // Keystore lỗi: khóa bọc không dùng lại được (blob đã xóa), khóa mới sẽ sinh ở lần mở sau.
                }
            }
        }
        _owner.value = null
        epoch.value++
    }

    private fun readOwner(): OutboxOwner? =
        prefs.getString(OWNER, null)?.let { runCatching { json.decodeFromString(OutboxOwner.serializer(), it) }.getOrNull() }

    private fun OutboxBatchEntity.toDomain(): OutboxBatch {
        val mutations = runCatching { json.decodeFromString(ListSerializer(OutboxMutation.serializer()), mutationsJson) }.getOrNull()
        // Dòng hỏng (không đọc được nội dung): đánh dấu lỗi riêng, không làm sập các lô khác.
        return OutboxBatch(
            idempotencyKey, sessionId, OutboxOwner(ownerUserId, tenantId), deviceId, deviceTime, mutations.orEmpty(),
            markSessionTaught, createdAt, attempts,
            state = if (mutations == null) OutboxState.Failed else OutboxState.fromWire(state),
            errorCode = if (mutations == null) "CORRUPT" else errorCode,
        )
    }

    private fun OutboxBatch.toEntity() = OutboxBatchEntity(
        idempotencyKey, sessionId, owner.userId, owner.tenantId, deviceId, deviceTime,
        json.encodeToString(ListSerializer(OutboxMutation.serializer()), mutations),
        markSessionTaught, createdAt, attempts, state.wire, errorCode,
    )

    private companion object {
        const val PREFS = "dayhoc_local_db"
        const val KEY_BLOB = "db_key"
        const val DEVICE_ID = "device_id"
        const val OWNER = "owner"
        const val USER_ID = "user_id"
        const val KEY_ALIAS = "dayhoc_local_db"
        const val DB_NAME = "dayhoc_local.db"
        const val ROSTER_TTL_MS = 14L * 24 * 60 * 60 * 1000
    }
}
