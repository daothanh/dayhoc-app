package vn.dayhoc.app.teacher.attendance

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError

@OptIn(ExperimentalCoroutinesApi::class)
class AttendanceViewModelTest {
    private val vnZone: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    private var now = Instant.parse("2026-11-09T10:45:00.123Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = vnZone
        override fun withZone(z: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val api = FakeAttendanceApi()
    private val outbox = InMemoryOutbox()
    private val roster = InMemoryRoster()
    private val net = FakeConnectivity()
    private val notices = mutableListOf<AttendanceNotice>()
    private var enqueued = 0
    private var seq = 0
    private var owner: OutboxOwner? = OWNER
    private val engine: AttendanceSyncEngine = AttendanceSyncEngine(api, outbox, roster) { OWNER }

    private val students = listOf(item("s1", "Nguyễn An"), item("s2", "Lê Chi"), item("s3", "Phạm Dũng"))

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(dto: SessionAttendanceDto = attendanceDto(students)): AttendanceViewModel {
        api.attendance = { dto }
        return AttendanceViewModel(
            "sess-1", api, outbox, roster, FixedDevice(), engine, net, { owner }, { notices += it }, { enqueued++ }, clock, { "id-${++seq}" },
        ).also { advanceUntilIdle() }
    }

    @Test
    fun `tai xong thi mac dinh moi hoc sinh co mat va nut Luu diem danh`() = runTest {
        val vm = vm()
        val s = vm.state.value
        assertFalse(s.loading)
        assertEquals(listOf(AttStatus.Present, AttStatus.Present, AttStatus.Present), s.rows.map { it.status })
        assertEquals(SaveLabel.Save, s.label)
        assertFalse(s.dirty)
        assertTrue(s.canSave)
        assertEquals(Counts(3, 0, 0), s.counts)
    }

    @Test
    fun `dong da co trang thai thi dung trang thai do va chua doi thi khong dirty`() = runTest {
        val vm = vm(attendanceDto(listOf(item("s1", "An", "late"), item("s2", "Chi", "unexcused")), status = "taught"))
        assertEquals(listOf(AttStatus.Late, AttStatus.Unexcused), vm.state.value.rows.map { it.status })
        assertTrue(vm.state.value.editMode)
        assertFalse(vm.state.value.dirty)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `doi trang thai cap nhat chip, bo dem, nhan nut va hang tu thu sau 300ms`() = runTest {
        val vm = vm()
        vm.toggle("s2")
        assertEquals("s2", vm.state.value.expandedId)
        vm.select("s2", AttStatus.Unexcused)
        assertEquals(Counts(2, 1, 0), vm.state.value.counts)
        assertEquals(SaveLabel.Notify(1), vm.state.value.label)
        assertEquals("s2", vm.state.value.expandedId)
        advanceTimeBy(299)
        assertEquals("s2", vm.state.value.expandedId)
        advanceTimeBy(2)
        assertNull(vm.state.value.expandedId)
    }

    @Test
    fun `chi mot hang mo cung luc`() = runTest {
        val vm = vm()
        vm.toggle("s1")
        vm.toggle("s2")
        assertEquals("s2", vm.state.value.expandedId)
    }

    @Test
    fun `luu online lan dau gui moi hoc sinh, markSessionTaught, deviceId, khoa idempotency, ve T03 kem thong bao`() = runTest {
        val vm = vm()
        vm.select("s2", AttStatus.Excused)
        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req, parents = 1) }
        vm.save()
        advanceUntilIdle()

        val (key, req) = api.syncCalls.single()
        assertEquals("device-1", req.deviceId)
        assertEquals("sess-1", req.sessionId)
        assertTrue(req.markSessionTaught)
        assertEquals("2026-11-09T17:45:00.123+07:00", req.deviceTime)
        assertEquals(listOf("s1", "s2", "s3"), req.mutations.map { it.studentId })
        assertEquals(listOf("present", "excused", "present"), req.mutations.map { it.status })
        assertEquals(3, req.mutations.map { it.clientMutationId }.toSet().size)
        assertTrue(key.isNotEmpty())
        assertEquals(listOf<AttendanceNotice>(AttendanceNotice.Saved(1)), notices)
        assertTrue(vm.state.value.done)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `che do sua chi gui hoc sinh doi`() = runTest {
        val vm = vm(attendanceDto(listOf(item("s1", "An", "present"), item("s2", "Chi", "present"), item("s3", "Dũng", "late")), status = "taught"))
        vm.select("s2", AttStatus.Unexcused)
        assertTrue(vm.state.value.canSave)
        assertEquals(SaveLabel.SaveChanges, vm.state.value.label)
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf("s2"), api.syncCalls.single().second.mutations.map { it.studentId })
    }

    @Test
    fun `loi 5xx hien loi co thu lai va thu lai giu nguyen khoa va clientMutationId`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        var fail = true
        api.syncHandler = { _, req -> if (fail) throw ApiError.Http("INTERNAL_ERROR", 500) else FakeAttendanceApi.okResult(req) }
        vm.save()
        advanceUntilIdle()
        assertEquals(SaveError(null, retry = true), vm.state.value.error)
        assertFalse(vm.state.value.saving)
        assertFalse(vm.state.value.done)

        fail = false
        vm.save()
        advanceUntilIdle()
        assertEquals(2, api.syncCalls.size)
        assertEquals(api.syncCalls[0].first, api.syncCalls[1].first)
        assertEquals(api.syncCalls[0].second.mutations, api.syncCalls[1].second.mutations)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `doi trang thai sau loi thi lo moi, khoa moi`() = runTest {
        val vm = vm()
        api.syncHandler = { _, _ -> throw ApiError.Http("INTERNAL_ERROR", 500) }
        vm.save()
        advanceUntilIdle()
        vm.select("s1", AttStatus.Late)
        vm.save()
        advanceUntilIdle()
        assertTrue(api.syncCalls[0].first != api.syncCalls[1].first)
    }

    @Test
    fun `cua so sua dong thi chi xem, ap cac muc da applied, lo bi xoa`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        vm.select("s2", AttStatus.Excused)
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map {
                if (it.studentId == "s2") MutationResultDto(it.clientMutationId, it.studentId, "rejected", "ATTENDANCE_EDIT_WINDOW_CLOSED")
                else MutationResultDto(it.clientMutationId, it.studentId, "applied")
            })
        }
        vm.save()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(SaveError("ATTENDANCE_EDIT_WINDOW_CLOSED", retry = false), s.error)
        assertEquals(ReadOnly.Expired, s.readOnly)
        assertEquals(AttStatus.Late, s.rows.first { it.studentId == "s1" }.original)
        assertFalse(s.canSave)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `buoi da huy thi chi xem va khong thu lai`() = runTest {
        val vm = vm()
        api.syncHandler = { _, _ -> throw ApiError.Http("SESSION_CANCELLED", 409) }
        vm.save()
        advanceUntilIdle()
        assertEquals(SaveError("SESSION_CANCELLED", retry = false), vm.state.value.error)
        assertEquals(ReadOnly.Cancelled, vm.state.value.readOnly)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `loi 422 giu lo failed trong outbox, hien loi, Gui lai la gui lai cung lo`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        api.syncHandler = { _, _ -> throw ApiError.Http("VALIDATION_FAILED", 422) }
        vm.save()
        advanceUntilIdle()
        assertEquals("VALIDATION_FAILED", vm.state.value.failedCode)
        assertEquals(OutboxState.Failed, outbox.all().single().state)
        assertTrue(vm.state.value.canSave)

        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req) }
        vm.save()
        advanceUntilIdle()
        assertEquals(api.syncCalls[0].first, api.syncCalls[1].first)
        assertEquals(api.syncCalls[0].second, api.syncCalls[1].second)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `mo lai buoi co lo failed - hien gia tri lo, original giu cua server, nut Gui lai`() = runTest {
        outbox.enqueue(batch("kf", "sess-1", state = OutboxState.Failed, errorCode = "FORBIDDEN"))
        val vm = vm()
        val s = vm.state.value
        assertEquals("FORBIDDEN", s.failedCode)
        assertEquals(AttStatus.Unexcused, s.rows.first { it.studentId == "s1" }.status)
        assertEquals(null, s.rows.first { it.studentId == "s1" }.original)
        assertFalse(s.editMode)
        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req) }
        vm.resend()
        advanceUntilIdle()
        assertEquals("kf", api.syncCalls.single().first)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `ket qua stale ap gia tri server, o lai man hinh va bao`() = runTest {
        val vm = vm()
        vm.select("s2", AttStatus.Unexcused)
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map {
                if (it.studentId == "s2") MutationResultDto(it.clientMutationId, it.studentId, "stale", current = CurrentDto("late", "Đến muộn", "2026-11-09T17:50:00+07:00", "Cô Lan"))
                else MutationResultDto(it.clientMutationId, it.studentId, "applied")
            })
        }
        vm.save()
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals(AttStatus.Late, s.rows.first { it.studentId == "s2" }.status)
        assertTrue(s.staleNotice)
        assertFalse(s.done)
        assertTrue(s.editMode)
        assertFalse(s.dirty)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `mat mang luc luu vao outbox, ve T03, bao da luu tren may`() = runTest {
        val vm = vm()
        vm.select("s2", AttStatus.Unexcused)
        api.syncHandler = { _, _ -> throw ApiError.Network }
        vm.save()
        advanceUntilIdle()

        val batch = outbox.all().single()
        assertEquals("sess-1", batch.sessionId)
        assertTrue(batch.markSessionTaught)
        assertEquals(listOf("present", "unexcused", "present"), batch.mutations.map { it.status })
        assertEquals(listOf<AttendanceNotice>(AttendanceNotice.SavedLocal), notices)
        assertEquals(1, enqueued)
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `dang ngoai tuyen thi nhan Luu tren may va khong goi API`() = runTest {
        val vm = vm()
        net.set(false)
        advanceUntilIdle()
        assertTrue(vm.state.value.offline)
        assertEquals(SaveLabel.SaveLocal, vm.state.value.label)
        vm.save()
        advanceUntilIdle()
        assertTrue(api.syncCalls.isEmpty())
        assertEquals(1, outbox.all().size)
        assertEquals(listOf<AttendanceNotice>(AttendanceNotice.SavedLocal), notices)
    }

    @Test
    fun `gui lai lo da luu giu dung khoa va clientMutationId da dung luc luu`() = runTest {
        val vm = vm()
        net.set(false)
        advanceUntilIdle()
        vm.select("s1", AttStatus.Excused)
        vm.save()
        advanceUntilIdle()
        val stored = outbox.all().single()
        assertEquals(3, stored.mutations.size)
        assertEquals(setOf("id-2", "id-3", "id-4"), stored.mutations.map { it.clientMutationId }.toSet())
        assertEquals("id-1", stored.idempotencyKey)
    }

    @Test
    fun `phat lai khi dong ho da doi thi body giong het, ke ca deviceTime va clientUpdatedAt`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        api.syncHandler = { _, _ -> throw ApiError.Http("SERVICE_UNAVAILABLE", 503) }
        vm.save()
        advanceUntilIdle()
        now = now.plusSeconds(120)
        api.syncHandler = { _, req -> FakeAttendanceApi.okResult(req) }
        vm.save()
        advanceUntilIdle()
        assertEquals(api.syncCalls[0].first, api.syncCalls[1].first)
        assertEquals(api.syncCalls[0].second, api.syncCalls[1].second)
        assertEquals("2026-11-09T17:45:00.123+07:00", api.syncCalls[1].second.deviceTime)
    }

    @Test
    fun `chua xac dinh chu thi khong luu, bao loi co Thu lai, co chu roi luu duoc`() = runTest {
        owner = null
        val vm = vm()
        vm.save()
        advanceUntilIdle()
        assertEquals(SaveError(AttendanceViewModel.OWNER_MISSING, retry = true), vm.state.value.error)
        assertTrue(api.syncCalls.isEmpty())
        assertTrue(outbox.all().isEmpty())
        owner = OWNER
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `lop khong co hoc sinh thi khong luu`() = runTest {
        assertFalse(vm(attendanceDto(emptyList())).state.value.canSave)
    }

    @Test
    fun `phan tu rejected ma khac thi lo van xong va bao ten hoc sinh`() = runTest {
        val vm = vm()
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map {
                if (it.studentId == "s3") MutationResultDto(it.clientMutationId, it.studentId, "rejected", "NOT_FOUND") else MutationResultDto(it.clientMutationId, it.studentId, "applied")
            })
        }
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf<AttendanceNotice>(AttendanceNotice.Saved(0, listOf("Phạm Dũng"))), notices)
        assertTrue(vm.state.value.done)
        assertTrue(outbox.all().isEmpty())
    }

    @Test
    fun `chu cua engine khac chu cua lo thi bao OWNER_MISSING va khong ghi outbox`() = runTest {
        val other = AttendanceSyncEngine(api, outbox, roster) { OutboxOwner("u2", "t1") }
        val vm2 = AttendanceViewModel("sess-1", api, outbox, roster, FixedDevice(), other, net, { OWNER }, { notices += it }, { enqueued++ }, clock, { "x-${++seq}" })
        api.attendance = { attendanceDto(students) }
        advanceUntilIdle()
        vm2.save()
        advanceUntilIdle()
        assertEquals(SaveError(AttendanceViewModel.OWNER_MISSING, retry = true), vm2.state.value.error)
        assertTrue(outbox.all().isEmpty())
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `thong bao hoc sinh bi tu choi chi hien mot lan - su kien Synced cua chinh lo T04 mang rejected = 0`() = runTest {
        val events = mutableListOf<SyncEvent>()
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            engine.events.collect { events += it }
        }
        val vm = vm()
        api.syncHandler = { _, req ->
            FakeAttendanceApi.okResult(req, results = req.mutations.map {
                if (it.studentId == "s3") MutationResultDto(it.clientMutationId, it.studentId, "rejected", "NOT_FOUND") else MutationResultDto(it.clientMutationId, it.studentId, "applied")
            })
        }
        vm.save()
        advanceUntilIdle()
        assertEquals(listOf<SyncEvent>(SyncEvent.Synced("sess-1", 0)), events)
        job.cancel()
    }

    @Test
    fun `doc outbox loi luc tai thi loadFailed, khong cho luu`() = runTest {
        outbox.failOnAll = true
        val vm = vm()
        assertTrue(vm.state.value.loadFailed)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `mo tu ban chup da qua han sua thi chi xem`() = runTest {
        vm()
        val dto = attendanceDto(students).copy(editableUntil = "2026-11-09T23:59:59+07:00")
        roster.map["sess-1"] = kotlinx.serialization.json.Json.encodeToString(SessionAttendanceDto.serializer(), dto)
        api.attendance = { throw ApiError.Network }
        now = Instant.parse("2026-11-10T01:00:00Z")
        val vm2 = AttendanceViewModel("sess-1", api, outbox, roster, FixedDevice(), engine, net, { OWNER }, {}, {}, clock)
        advanceUntilIdle()
        assertEquals(ReadOnly.Expired, vm2.state.value.readOnly)
    }

    @Test
    fun `bo thay doi xoa moi lo cua man ke ca lo bi thay, loi xoa thi o lai va bao`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        api.syncHandler = { _, _ -> throw ApiError.Http("SERVICE_UNAVAILABLE", 503) }
        vm.save()
        advanceUntilIdle()
        vm.select("s2", AttStatus.Late) // lô cũ bị thay
        assertEquals(1, outbox.all().size)
        vm.askDiscard()
        vm.discard()
        advanceUntilIdle()
        assertTrue(outbox.all().isEmpty())
        assertTrue(vm.state.value.done)

        val failing = object : OutboxStore by outbox {
            override suspend fun remove(idempotencyKey: String) = error("lỗi đĩa")
        }
        val vm3 = AttendanceViewModel("sess-1", api, failing, roster, FixedDevice(), engine, net, { OWNER }, {}, {}, clock, { "z-${++seq}" })
        advanceUntilIdle()
        vm3.select("s1", AttStatus.Late)
        api.syncHandler = { _, _ -> throw ApiError.Http("SERVICE_UNAVAILABLE", 503) }
        vm3.save()
        advanceUntilIdle()
        vm3.askDiscard()
        vm3.discard()
        advanceUntilIdle()
        assertEquals(SaveError(AttendanceViewModel.DISCARD_FAILED, retry = false), vm3.state.value.error)
        assertFalse(vm3.state.value.done)
    }

    @Test
    fun `dang luu thi khong hoi bo thay doi va khong bo`() = runTest {
        val vm = vm()
        vm.select("s1", AttStatus.Late)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        api.syncHandler = { _, req -> gate.await(); FakeAttendanceApi.okResult(req) }
        vm.save()
        advanceUntilIdle()
        assertTrue(vm.state.value.saving)
        vm.askDiscard()
        vm.discard()
        assertFalse(vm.state.value.confirmDiscard)
        assertFalse(vm.state.value.done)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
    }

    @Test
    fun `back khi co thay doi hoi bo thay doi, o lai hoac bo`() = runTest {
        val vm = vm()
        assertFalse(vm.state.value.dirty)
        vm.select("s1", AttStatus.Late)
        assertTrue(vm.state.value.dirty)
        vm.askDiscard()
        assertTrue(vm.state.value.confirmDiscard)
        vm.stay()
        assertFalse(vm.state.value.confirmDiscard)
        assertFalse(vm.state.value.done)
        vm.askDiscard()
        vm.discard()
        advanceUntilIdle()
        assertTrue(vm.state.value.done)
        assertTrue(api.syncCalls.isEmpty())
    }

    @Test
    fun `buoi qua 7 ngay hoac da huy chi xem, khong luu va khong mo hang`() = runTest {
        val expired = vm(attendanceDto(students, status = "taught", canEdit = false))
        assertEquals(ReadOnly.Expired, expired.state.value.readOnly)
        expired.toggle("s1")
        assertNull(expired.state.value.expandedId)
        assertFalse(expired.state.value.canSave)

        val cancelled = vm(attendanceDto(students, status = "cancelled", canEdit = false))
        assertEquals(ReadOnly.Cancelled, cancelled.state.value.readOnly)
    }

    @Test
    fun `tai loi mang thi mo bang ban chup, khong co ban chup thi bao loi`() = runTest {
        val first = vm()
        assertTrue(roster.map.containsKey("sess-1"))
        assertFalse(first.state.value.loadFailed)

        api.attendance = { throw ApiError.Network }
        val offline = AttendanceViewModel("sess-1", api, outbox, roster, FixedDevice(), engine, net, { OWNER }, {}, {}, clock)
        advanceUntilIdle()
        assertEquals(3, offline.state.value.rows.size)
        assertFalse(offline.state.value.loadFailed)

        roster.map.clear()
        val none = AttendanceViewModel("sess-1", api, outbox, roster, FixedDevice(), engine, net, { OWNER }, {}, {}, clock)
        advanceUntilIdle()
        assertTrue(none.state.value.loadFailed)
    }

    @Test
    fun `lo dang cho trong outbox - original la gia tri lo, editMode, khong dirty`() = runTest {
        outbox.enqueue(batch("k", "sess-1"))
        val vm = vm()
        val row = vm.state.value.rows.first { it.studentId == "s1" }
        assertEquals(AttStatus.Unexcused, row.status)
        assertEquals(AttStatus.Unexcused, row.original)
        assertTrue(vm.state.value.editMode)
        assertFalse(vm.state.value.dirty)
    }
}
