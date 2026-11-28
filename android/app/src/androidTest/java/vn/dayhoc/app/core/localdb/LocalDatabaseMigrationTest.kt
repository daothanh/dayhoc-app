package vn.dayhoc.app.core.localdb

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Migration 1 → 2 giữ dữ liệu cũ và khớp schema đã xuất (`app/schemas`). Chạy ở instrumentation, dùng SQLite hệ thống. */
@RunWith(AndroidJUnit4::class)
class LocalDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LocalDatabase::class.java)

    @Test
    fun migrate1To2KeepsRowsAndAddsOwnerColumns() {
        helper.createDatabase(NAME, 1).apply {
            execSQL(
                "INSERT INTO outbox_batches (idempotencyKey, sessionId, mutationsJson, markSessionTaught, createdAt, attempts) " +
                    "VALUES ('k1', 's1', '[]', 1, 10, 2)",
            )
            execSQL("INSERT INTO roster_cache (sessionId, json, cachedAt) VALUES ('s1', '{}', 5)")
            close()
        }

        val db = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)

        db.query("SELECT ownerUserId, tenantId, deviceId, deviceTime, state, errorCode, attempts FROM outbox_batches WHERE idempotencyKey = 'k1'").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("", it.getString(0))
            assertEquals("", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals("", it.getString(3))
            assertEquals("pending", it.getString(4))
            assertEquals(true, it.isNull(5))
            assertEquals(2, it.getInt(6))
        }
        db.query("SELECT ownerUserId, tenantId, json, cachedAt FROM roster_cache WHERE sessionId = 's1'").use {
            assertEquals(true, it.moveToFirst())
            assertEquals("", it.getString(0))
            assertEquals("", it.getString(1))
            assertEquals("{}", it.getString(2))
            assertEquals(5L, it.getLong(3))
        }
    }

    private companion object {
        const val NAME = "migration-test"
    }
}
