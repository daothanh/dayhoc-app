package vn.dayhoc.app.teacher.attendance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError

/** Quy tắc giữ/xóa/failed nằm trong fixtures (`flush_rules.json`); đây là các ca về khóa, thứ tự và lỗi bất ngờ. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceSyncEngineTest {
    private val api = FakeAttendanceApi()
    private val roster = InMemoryRoster()
    private var owner: OutboxOwner? = OWNER

    private fun engine(outbox: OutboxStore) = AttendanceSyncEngine(api, outbox, roster) { owner }

    @Test
    fun `dung khoa, body va moc gio da luu khi phat lai`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        val e = engine(outbox)
        api.syncHandler = { _, _ -> throw ApiError.Network }
        e.flush()
        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req) }
        e.flush()
        assertEquals(2, api.syncCalls.size)
        assertEquals(api.syncCalls[0], api.syncCalls[1])
        assertEquals("2026-11-09T17:40:00.000+07:00", api.syncCalls[1].second.deviceTime)
        assertEquals("m-k1", api.syncCalls[1].second.mutations.single().clientMutationId)
    }

    @Test
    fun `lan goi den luc ban thi cho roi chay lai, lo moi vao trong luc do van duoc gui`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        val gate = CompletableDeferred<Unit>()
        api.syncHandler = { key, req -> if (key == "k1") gate.await(); FakeAttendanceApi.okResult(req) }
        val e = engine(outbox)
        val first = async { e.flush() }
        advanceUntilIdle()
        outbox.enqueue(batch("k2", "sess-2"))
        val second = async { e.flush() }
        advanceUntilIdle()
        gate.complete(Unit)
        assertEquals(FlushResult.Done, first.await().result)
        assertEquals(FlushResult.Done, second.await().result)
        assertEquals(listOf("k1", "k2"), api.syncCalls.map { it.first })
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `enqueueAndFlush ghi outbox roi gui trong mot lan giu khoa va tra ket qua cua lo`() = runTest {
        val outbox = InMemoryOutbox()
        val report = engine(outbox).enqueueAndFlush(batch("k1"))
        assertTrue(report.outcomes["k1"] is BatchOutcome.Synced)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `chu doi hoac mat truoc khi gui thi khong gui`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        owner = null
        assertEquals(FlushResult.Done, engine(outbox).flush().result)
        assertTrue(api.syncCalls.isEmpty())
        assertEquals(1, outbox.all().size)
    }

    @Test
    fun `lo bi bo khoi outbox truoc luc gui thi bo qua`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1"), batch("k2", "sess-2")))
        val gate = CompletableDeferred<Unit>()
        api.syncHandler = { key, req -> if (key == "k1") { outbox.remove("k2"); gate.complete(Unit) }; FakeAttendanceApi.okResult(req) }
        engine(outbox).flush()
        assertEquals(listOf("k1"), api.syncCalls.map { it.first })
    }

    @Test
    fun `loi doc outbox thi Retry, khong sap`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        outbox.failOnAll = true
        assertEquals(FlushResult.Retry, engine(outbox).flush().result)
    }

    @Test
    fun `loi DB sau 200 khong tinh lan thu va giu lo pending`() = runTest {
        val outbox = object : OutboxStore by InMemoryOutbox(listOf(batch("k1"))) {
            override suspend fun remove(idempotencyKey: String) = error("đĩa đầy")
        }
        assertEquals(FlushResult.Retry, engine(outbox).flush().result)
        val left = outbox.all().single()
        assertEquals(0, left.attempts)
        assertEquals(OutboxState.Pending, left.state)
    }

    @Test
    fun `dong outbox giai ma hong la failed CORRUPT thi bi bo qua khong chan buoi khac`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1", state = OutboxState.Failed, errorCode = "CORRUPT"), batch("k2", "sess-2")))
        assertEquals(FlushResult.Done, engine(outbox).flush().result)
        assertEquals(listOf("k2"), api.syncCalls.map { it.first })
        assertEquals("CORRUPT", outbox.all().single().errorCode)
    }

    @Test
    fun `exclusive doi lan gui dang chay xong`() = runTest {
        val outbox = InMemoryOutbox(listOf(batch("k1")))
        val gate = CompletableDeferred<Unit>()
        api.syncHandler = { _, req -> gate.await(); FakeAttendanceApi.okResult(req) }
        val e = engine(outbox)
        val flush = async { e.flush() }
        advanceUntilIdle()
        var wiped = false
        val wipe = async { e.exclusive { wiped = true } }
        advanceUntilIdle()
        assertTrue(!wiped)
        gate.complete(Unit)
        flush.await()
        wipe.await()
        assertTrue(wiped)
    }
}
