package vn.dayhoc.app.teacher.attendance

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Test
import vn.dayhoc.app.core.network.ApiError
import vn.dayhoc.app.teacher.today.SessionKind
import vn.dayhoc.app.teacher.today.TodaySession
import vn.dayhoc.app.teacher.today.mergePending

/** Chạy các ca trong `fixtures/attendance/` (dùng chung với iOS, định dạng ở README cùng thư mục). */
class AttendanceFixturesTest {
    private fun fixture(name: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null && !File(dir, "fixtures/attendance/$name").exists()) dir = dir.parentFile
        checkNotNull(dir) { "Không thấy fixtures/attendance/$name từ ${System.getProperty("user.dir")}" }
        return Json.parseToJsonElement(File(dir, "fixtures/attendance/$name").readText()).jsonObject
    }

    private fun JsonObject.cases(key: String = "cases") = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.str(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
    private fun JsonObject.obj(k: String) = getValue(k).jsonObject

    private fun rows(a: JsonElement) = a.jsonArray.map {
        val o = it.jsonObject
        AttRow(
            o.str("studentId")!!, o.str("name")!!, AttStatus.fromWire(o.str("status"))!!, AttStatus.fromWire(o.str("original")),
            o.str("note"), o["parentReported"]?.jsonPrimitive?.boolean ?: false,
        )
    }

    @Test
    fun `dung mutations`() = fixture("mutations.json").cases().forEach { c ->
        val ids = c.getValue("ids").jsonArray.map { it.jsonPrimitive.content }.iterator()
        val got = buildMutations(rows(c.getValue("rows")), c.getValue("editMode").jsonPrimitive.boolean, { ids.next() }, c.str("now")!!)
        val want = c.getValue("expected").jsonArray.map {
            val o = it.jsonObject
            OutboxMutation(o.str("clientMutationId")!!, o.str("studentId")!!, o.str("status")!!, o.str("note"), o.str("clientUpdatedAt")!!)
        }
        assertEquals(c.str("name"), want, got)
    }

    @Test
    fun `bo dem, so phu huynh va nhan nut`() = fixture("notify_count.json").cases().forEach { c ->
        val rows = rows(c.getValue("rows"))
        val want = c.obj("expected")
        val name = c.str("name")
        assertEquals(name, want.getValue("notifyCount").jsonPrimitive.int, notifyCount(rows))
        val counts = want.obj("counts")
        assertEquals(name, Counts(counts.getValue("present").jsonPrimitive.int, counts.getValue("absent").jsonPrimitive.int, counts.getValue("late").jsonPrimitive.int), countsOf(rows))
        val label = want.obj("label")
        val expected = when (label.str("kind")) {
            "Save" -> SaveLabel.Save
            "Notify" -> SaveLabel.Notify(label.getValue("n").jsonPrimitive.int)
            "SaveChanges" -> SaveLabel.SaveChanges
            else -> SaveLabel.SaveLocal
        }
        assertEquals(name, expected, saveLabel(rows, c.getValue("editMode").jsonPrimitive.boolean, c.getValue("offline").jsonPrimitive.boolean))
    }

    @Test
    fun `ap ket qua server vao danh sach`() = fixture("apply_results.json").cases().forEach { c ->
        val mutations = c.getValue("mutations").jsonArray.mapIndexed { i, m ->
            OutboxMutation("m$i", m.jsonObject.str("studentId")!!, m.jsonObject.str("status")!!, null, "t")
        }
        val results = c.getValue("results").jsonArray.mapIndexed { i, r ->
            val o = r.jsonObject
            val cur = o["current"]?.jsonObject?.let { CurrentDto(it.str("status")!!, it.str("note")) }
            val target = o["forMutation"]?.jsonPrimitive?.int ?: i
            MutationResultDto("m$target", mutations[target].studentId, o.str("result")!!, current = cur)
        }
        val got = applyResults(rows(c.getValue("rows")), mutations, results)
        val want = c.obj("expected")
        assertEquals(c.str("name"), want.getValue("staleCount").jsonPrimitive.int, got.staleCount)
        val wantRows = want.getValue("rows").jsonArray.map { it.jsonObject }
        assertEquals(c.str("name"), wantRows.map { it.str("studentId") }, got.rows.map { it.studentId })
        assertEquals(c.str("name"), wantRows.map { it.str("status") }, got.rows.map { it.status.wire })
        assertEquals(c.str("name"), wantRows.map { it.str("original") }, got.rows.map { it.original?.wire })
        assertEquals(c.str("name"), wantRows.map { it.str("note") }, got.rows.map { it.note })
    }

    @Test
    fun `gop lo cho dong bo vao the buoi`() = fixture("pending_merge.json").cases().forEach { c ->
        val t = Instant.parse("2026-11-09T10:00:00Z")
        val sessions = c.getValue("sessions").jsonArray.map {
            TodaySession(it.jsonObject.str("id")!!, "Lớp", t, t, SessionKind.valueOf(it.jsonObject.str("kind")!!), null, null, null, null)
        }
        val pending = c.getValue("pendingIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val failed = c["failedIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
        val got = mergePending(sessions, pending, failed).map { listOf(it.id, it.kind.name, it.pending, it.syncFailed) }
        val want = c.getValue("expected").jsonArray.map {
            val o = it.jsonObject
            listOf(o.str("id")!!, o.str("kind")!!, o.getValue("pending").jsonPrimitive.boolean, o["syncFailed"]?.jsonPrimitive?.boolean ?: false)
        }
        assertEquals(c.str("name"), want, got)
    }

    @Test
    fun `giu hay bo lo theo loi`() {
        val f = fixture("error_decision.json")
        f.cases().forEach { c ->
            val e = c.obj("error")
            val error: ApiError = when (e.str("type")) {
                "network" -> ApiError.Network
                "timeout" -> ApiError.Timeout
                "invalid" -> ApiError.InvalidResponse
                else -> ApiError.Http(e.str("code")!!, e.getValue("httpStatus").jsonPrimitive.int)
            }
            val want = c.obj("expected")
            val expected = if (want.str("decision") == "keep") ErrorDecision.Keep(want.getValue("countAttempt").jsonPrimitive.boolean)
            else ErrorDecision.Drop(want.str("code")!!)
            assertEquals(c.str("name"), expected, decideOnError(error, c.getValue("attempts").jsonPrimitive.int))
        }
        f.cases("deletable").forEach { c -> assertEquals(c.str("code"), c.getValue("expected").jsonPrimitive.boolean, isDeletableError(c.str("code")!!)) }
        f.cases("batch_verdict").forEach { c ->
            val results = c.getValue("results").jsonArray.mapIndexed { i, r -> MutationResultDto("m$i", "s$i", r.jsonObject.str("result")!!, r.jsonObject.str("errorCode")) }
            val want = c.obj("expected")
            val expected = if (want.str("verdict") == "done") BatchVerdict.Done else BatchVerdict.Rejected(want.str("code")!!)
            assertEquals(c.str("name"), expected, verdictOf(results))
            want["rejectedStudentIds"]?.let { ids ->
                assertEquals(c.str("name"), ids.jsonArray.map { it.jsonPrimitive.content }, rejectedStudentIds(results))
            }
        }
    }

    @Test
    fun `gui outbox theo luat flush`() = fixture("flush_rules.json").let { f ->
        val shared = f.obj("owner").let { OutboxOwner(it.str("userId")!!, it.str("tenantId")!!) }
        f.cases().forEach { c -> runFlushCase(c, if (c.containsKey("owner")) c["owner"]?.takeIf { it !is JsonNull }?.jsonObject?.let { OutboxOwner(it.str("userId")!!, it.str("tenantId")!!) } else shared) }
    }

    private fun runFlushCase(c: JsonObject, owner: OutboxOwner?) = kotlinx.coroutines.test.runTest {
        val name = c.str("name")
        val outbox = InMemoryOutbox(c.getValue("batches").jsonArray.map { it.jsonObject }.map {
            batch(
                it.str("key")!!, it.str("sessionId")!!, it.getValue("attempts").jsonPrimitive.int, OutboxOwner(it.str("userId")!!, it.str("tenantId")!!),
                OutboxState.fromWire(it.str("state")), it.str("errorCode"),
            )
        })
        val roster = InMemoryRoster().also { r -> outbox.all().forEach { r.map[it.sessionId] = "{}" } }
        val script = c.obj("script")
        val api = FakeAttendanceApi()
        api.syncHandler = { key, req ->
            when (val step = script.str(key) ?: "ok") {
                "ok" -> FakeAttendanceApi.okResult(req)
                "network" -> throw ApiError.Network
                else -> {
                    val parts = step.split(":", limit = 3)
                    if (parts[0] == "http") throw ApiError.Http(parts[2], parts[1].toInt())
                    FakeAttendanceApi.okResult(req, results = req.mutations.mapIndexed { i, m ->
                        if (i == 0) MutationResultDto(m.clientMutationId, m.studentId, "rejected", parts[1]) else MutationResultDto(m.clientMutationId, m.studentId, "applied")
                    })
                }
            }
        }
        val events = mutableListOf<SyncEvent>()
        val engine = AttendanceSyncEngine(api, outbox, roster) { owner }
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { engine.events.toList(events) }
        val before = outbox.all().associate { it.idempotencyKey to it.attempts }
        val sessions = outbox.all().map { it.sessionId }.toSet()
        val result = engine.flush().result
        val want = c.obj("expected")

        assertEquals(name, want.getValue("sent").jsonArray.map { it.jsonPrimitive.content }, api.syncCalls.map { it.first })
        assertEquals(name, want.getValue("result").jsonPrimitive.content.replaceFirstChar { it.uppercase() }, result.name)
        val remaining = outbox.all()
        assertEquals(name, want.getValue("remaining").jsonArray.map { it.jsonObject.str("key") }, remaining.map { it.idempotencyKey })
        assertEquals(name, want.getValue("remaining").jsonArray.map { it.jsonObject.str("state") }, remaining.map { it.state.wire })
        assertEquals(name, want.getValue("remaining").jsonArray.map { it.jsonObject.str("errorCode") }, remaining.map { it.errorCode })
        want["attemptsAdded"]?.jsonArray?.map { it.jsonPrimitive.content }?.forEach { k ->
            assertEquals(name, before.getValue(k) + 1, remaining.first { it.idempotencyKey == k }.attempts)
        }
        val removed = want.getValue("rosterRemoved").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(name, removed.sorted(), sessions.filter { it !in roster.map.keys }.sorted())
        want["events"]?.jsonArray?.map { it.jsonPrimitive.content }?.let { expected ->
            assertEquals(name, expected, events.map { e ->
                when (e) {
                    is SyncEvent.Synced -> "synced:${e.sessionId}:rejected=${e.rejected}"
                    is SyncEvent.Dropped -> "dropped:${e.sessionId}:${e.code}"
                }
            })
        }
        job.cancel()
    }

    @Test
    fun `sheet chi tiet buoi`() {
        val f = fixture("sheet.json")
        f.getValue("sheet_action").jsonArray.map { it.jsonObject }.forEach { c ->
            assertEquals(SheetAction.valueOf(c.str("expected")!!), sheetAction(c.str("status")!!, c.getValue("pending").jsonPrimitive.boolean))
        }
        f.getValue("repeat_label").jsonArray.map { it.jsonObject }.forEach { c ->
            assertEquals(c.str("expected"), repeatLabel(c.getValue("weekdays").jsonArray.map { it.jsonPrimitive.int }))
        }
    }
}
