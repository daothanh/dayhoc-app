package vn.dayhoc.app.teacher.attendance

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceSyncEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:45:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"))
    private val api = FakeAttendanceApi()

    private fun batch(key: String, session: String = "sess-1", attempts: Int = 0) = OutboxBatch(
        key, session, listOf(OutboxMutation("m-$key", "s1", "unexcused", null, "2026-11-09T17:40:00+07:00")), true, 1, attempts,
    )

    private fun engine(outbox: OutboxStore) = AttendanceSyncEngine(api, outbox, FixedDevice(), clock)

    @Test
    fun `thanh cong xoa khoi outbox, dung khoa va clientMutationId da luu`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        val e = engine(outbox)
        val events = mutableListOf<SyncEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { e.events.toList(events) }

        assertEquals(FlushResult.Done, e.flush())

        val (key, req) = api.syncCalls.single()
        assertEquals("k1", key)
        assertEquals("m-k1", req.mutations.single().clientMutationId)
        assertEquals("2026-11-09T17:40:00+07:00", req.mutations.single().clientUpdatedAt)
        assertEquals("device-1", req.deviceId)
        assertTrue(outbox.all().isEmpty())
        assertEquals(listOf<SyncEvent>(SyncEvent.Synced("sess-1")), events)
        job.cancel()
    }

    @Test
    fun `duplicate van la thanh cong va xoa lo`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map { MutationResultDto(it.clientMutationId, it.studentId, "duplicate") })
        }
        assertEquals(FlushResult.Done, engine(outbox).flush())
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `loi mang giu lo, dung lai, khong tinh lan thu, lo sau khong vuot lo truoc`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1"), batch("k2", "sess-2")))
        api.syncHandler = { _, _ -> throw ApiError.Network }
        assertEquals(FlushResult.Retry, engine(outbox).flush())
        assertEquals(1, api.syncCalls.size)
        assertEquals(listOf("k1", "k2"), outbox.all().map { it.idempotencyKey })
        assertEquals(0, outbox.all().first().attempts)
    }

    @Test
    fun `loi 5xx giu lo va tinh lan thu, thu lai cung khoa`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        api.syncHandler = { _, _ -> throw ApiError.Http("INTERNAL_ERROR", 500) }
        val e = engine(outbox)
        assertEquals(FlushResult.Retry, e.flush())
        assertEquals(1, outbox.all().single().attempts)
        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req) }
        assertEquals(FlushResult.Done, e.flush())
        assertEquals(listOf("k1", "k1"), api.syncCalls.map { it.first })
    }

    @Test
    fun `loi 4xx khong thu lai bo lo va bao, lo sau van gui`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1"), batch("k2", "sess-2")))
        api.syncHandler = { key, req -> if (key == "k1") throw ApiError.Http("SESSION_CANCELLED", 409) else FakeAttendanceApi.okResult(req) }
        val e = engine(outbox)
        val events = mutableListOf<SyncEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { e.events.toList(events) }

        assertEquals(FlushResult.Done, e.flush())

        assertTrue(outbox.all().isEmpty())
        assertEquals(listOf<SyncEvent>(SyncEvent.Dropped("sess-1", "SESSION_CANCELLED"), SyncEvent.Synced("sess-2")), events)
        job.cancel()
    }

    @Test
    fun `phan tu bi tu choi cua so sua dong thi bo lo`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map { MutationResultDto(it.clientMutationId, it.studentId, "rejected", "ATTENDANCE_EDIT_WINDOW_CLOSED") })
        }
        val e = engine(outbox)
        val events = mutableListOf<SyncEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { e.events.toList(events) }
        e.flush()
        assertTrue(outbox.all().isEmpty())
        assertEquals(listOf<SyncEvent>(SyncEvent.Dropped("sess-1", "ATTENDANCE_EDIT_WINDOW_CLOSED")), events)
        job.cancel()
    }

    @Test
    fun `lo da qua 9 lan loi server thi bo o lan thu 10`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1", attempts = 9)))
        api.syncHandler = { _, _ -> throw ApiError.Http("INTERNAL_ERROR", 500) }
        assertEquals(FlushResult.Done, engine(outbox).flush())
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `khong chay chong, lan thu hai tra Busy va khong gui trung`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        val gate = CompletableDeferred<Unit>()
        api.syncHandler = { _, req -> gate.await(); FakeAttendanceApi.okResult(req) }
        val e = engine(outbox)
        val first = async { e.flush() }
        advanceUntilIdle()
        assertEquals(FlushResult.Busy, e.flush())
        gate.complete(Unit)
        assertEquals(FlushResult.Done, first.await())
        assertEquals(1, api.syncCalls.size)
    }
}
