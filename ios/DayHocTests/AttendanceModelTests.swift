import Foundation
import Testing
@testable import DayHoc

/// Cùng ca kiểm thử với `AttendanceViewModelTest` bên Android, theo thiết kế outbox đã sửa
/// (ghi outbox trước rồi SyncEngine gửi; lô lưu nguyên body; lô lỗi giữ lại).
@MainActor
struct AttendanceModelTests {
    private let vn = TimeZone(identifier: "Asia/Ho_Chi_Minh")!
    private let service = FakeService()
    private let outbox = InMemoryOutbox()
    private let roster = InMemoryRoster()
    private let net = FakeConnectivity()
    private let students = [item("s1", "Nguyễn An"), item("s2", "Lê Chi"), item("s3", "Phạm Dũng")]
    private let box = Box()

    @MainActor final class Box {
        var notices: [AttendanceNotice] = []
        var enqueued = 0
        var seq = 0
        var clock = Date(timeIntervalSince1970: 1_794_221_100.123) // 2026-11-09T10:45:00.123Z
        var wake: [CheckedContinuation<Void, Never>] = []
    }

    private func model(_ dto: SessionAttendanceDTO? = nil, load: Bool = true, manualCollapse: Bool = false, owner: OutboxOwner? = testOwner) async -> AttendanceModel {
        let dto = dto ?? attendanceDto(students)
        service.setGet { dto }
        let box = box
        let sleeper: @Sendable (Duration) async throws -> Void
        if manualCollapse {
            sleeper = { _ in await withCheckedContinuation { c in Task { @MainActor in box.wake.append(c) } } }
        } else {
            sleeper = { _ in }
        }
        let m = AttendanceModel(
            sessionId: "sess-1", service: service, outbox: outbox, roster: roster, device: FixedDevice(),
            engine: makeEngine(service, outbox, roster, owner: owner), connectivity: net, ownerProvider: { owner },
            notify: { box.notices.append($0) }, onEnqueued: { box.enqueued += 1 },
            now: { box.clock }, timeZone: vn, newId: { box.seq += 1; return "id-\(box.seq)" }, sleeper: sleeper
        )
        if load { await m.load() }
        return m
    }

    @Test func loadedDefaultsEveryoneToPresent() async {
        let m = await model()
        #expect(!m.loading)
        #expect(m.rows.map(\.status) == [.present, .present, .present])
        #expect(m.label == .save)
        #expect(!m.dirty)
        #expect(m.canSave)
        #expect(m.counts == Counts(present: 3, absent: 0, late: 0))
    }

    @Test func rowsWithServerStatusAreEditModeAndNotDirty() async {
        let m = await model(attendanceDto([item("s1", "An", "late"), item("s2", "Chi", "unexcused")], status: "taught"))
        #expect(m.rows.map(\.status) == [.late, .unexcused])
        #expect(m.editMode)
        #expect(!m.dirty)
        #expect(!m.canSave)
    }

    @Test func zeroStudentsCannotSave() async {
        let m = await model(attendanceDto([]))
        #expect(!m.canSave)
    }

    @Test func selectUpdatesChipCountersLabelAndCollapsesAfterDelay() async {
        let m = await model(manualCollapse: true)
        m.toggle("s2")
        #expect(m.expandedId == "s2")
        m.select("s2", .unexcused)
        #expect(m.counts == Counts(present: 2, absent: 1, late: 0))
        #expect(m.label == .notify(1))
        #expect(m.expandedId == "s2") // chưa tới 300ms
        while box.wake.isEmpty { await Task.yield() }
        box.wake.removeFirst().resume()
        while m.expandedId != nil { await Task.yield() }
    }

    @Test func onlyOneRowOpenAtATime() async {
        let m = await model()
        m.toggle("s1")
        m.toggle("s2")
        #expect(m.expandedId == "s2")
    }

    @Test func onlineSaveWritesOutboxFirstThenSendsFullBodyAndClosesWithNotice() async {
        let m = await model()
        m.select("s2", .excused)
        service.setSync { _, req in FakeService.ok(req, parents: 1) }
        await m.save()

        let call = service.syncCalls.single
        #expect(call?.body.deviceId == "device-1")
        #expect(call?.body.sessionId == "sess-1")
        #expect(call?.body.markSessionTaught == true)
        #expect(call?.body.deviceTime == "2026-11-09T17:45:00.123+07:00")
        #expect(call?.body.mutations.map(\.studentId) == ["s1", "s2", "s3"])
        #expect(call?.body.mutations.map(\.status) == ["present", "excused", "present"])
        #expect(call?.body.mutations.map(\.clientUpdatedAt).allSatisfy { $0 == "2026-11-09T17:45:00.123+07:00" } == true)
        #expect(call?.key == "id-1")
        #expect(box.notices == [.saved(parentsToNotify: 1, rejectedNames: [])])
        #expect(m.done)
        #expect(await outbox.snapshot().isEmpty)
        #expect(await roster.map["sess-1"] == nil) // cache buổi bị bỏ sau khi đồng bộ
    }

    @Test func editModeSendsOnlyChangedStudents() async {
        let m = await model(attendanceDto([item("s1", "An", "present"), item("s2", "Chi", "present"), item("s3", "Dũng", "late")], status: "taught"))
        m.select("s2", .unexcused)
        #expect(m.canSave)
        #expect(m.label == .saveChanges)
        service.setSync { _, req in FakeService.ok(req) }
        await m.save()
        #expect(service.syncCalls.single?.body.mutations.map(\.studentId) == ["s2"])
    }

    @Test func serverErrorKeepsBatchAndRetryReplaysSameKeyAndBodyEvenIfClockMoved() async {
        let m = await model()
        m.select("s1", .late)
        let fail = FailSwitch()
        service.setSync { _, req in
            if fail.on { throw httpError(500, "INTERNAL_ERROR") }
            return FakeService.ok(req)
        }
        await m.save()
        #expect(m.error == SaveError(code: nil, retry: true))
        #expect(!m.saving)
        #expect(!m.done)
        #expect(await outbox.snapshot().count == 1)

        fail.on = false
        box.clock = box.clock.addingTimeInterval(60)
        await m.save()
        let calls = service.syncCalls
        #expect(calls.count == 2)
        #expect(calls[0].key == calls[1].key)
        #expect(calls[0].body == calls[1].body) // gồm cả deviceTime: backend băm cả body
        #expect(m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func changingStatusAfterErrorBuildsNewBatchAndDropsOld() async {
        let m = await model()
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        await m.save()
        m.select("s1", .late)
        await m.save()
        let keys = service.syncCalls.map { $0.key }
        #expect(keys.count == 2 && keys[0] != keys[1])
        let left = await outbox.snapshot()
        #expect(left.map(\.idempotencyKey) == [keys[1]])
    }

    @Test func editWindowClosedAndCancelledShowOwnErrorAndBecomeReadOnly() async {
        let m = await model()
        service.setSync { _, req in FakeService.ok(req, results: FakeService.each(req, "rejected", code: "ATTENDANCE_EDIT_WINDOW_CLOSED")) }
        await m.save()
        #expect(m.error == SaveError(code: "ATTENDANCE_EDIT_WINDOW_CLOSED", retry: false))
        #expect(m.readOnly == .expired)
        #expect(!m.canSave)
        #expect(await outbox.snapshot().isEmpty)

        let m2 = await model()
        service.setSync { _, _ in throw httpError(409, "SESSION_CANCELLED") }
        await m2.save()
        #expect(m2.error == SaveError(code: "SESSION_CANCELLED", retry: false))
        #expect(m2.readOnly == .cancelled)
    }

    @Test func otherPermanentErrorKeepsFailedBatchAndOffersResend() async {
        let m = await model()
        m.select("s1", .late)
        service.setSync { _, _ in throw httpError(422, "VALIDATION_ERROR") }
        await m.save()
        #expect(m.failedCode == "VALIDATION_ERROR")
        let left = await outbox.snapshot()
        #expect(left.single?.state == .failed)
        #expect(m.readOnly == nil)

        service.setSync { _, req in FakeService.ok(req) }
        await m.resend()
        #expect(m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func staleResultAppliesServerValueStaysAndNotifies() async {
        let m = await model()
        m.select("s2", .unexcused)
        service.setSync { _, req in
            FakeService.ok(req, results: req.mutations.map {
                $0.studentId == "s2"
                    ? MutationResultDTO(clientMutationId: $0.clientMutationId, studentId: $0.studentId, result: "stale", current: CurrentDTO(status: "late", note: "Đến muộn"))
                    : MutationResultDTO(clientMutationId: $0.clientMutationId, studentId: $0.studentId, result: "applied")
            })
        }
        await m.save()
        #expect(m.rows.first { $0.studentId == "s2" }?.status == .late)
        #expect(box.notices == [.stale])
        #expect(!m.done)
        #expect(m.editMode)
        #expect(!m.dirty)
    }

    @Test func networkLossWhileSavingStaysInOutboxAndClosesWithLocalNotice() async {
        let m = await model()
        m.select("s2", .unexcused)
        service.setSync { _, _ in throw APIError(kind: .offline) }
        await m.save()
        let batch = await outbox.snapshot().single
        #expect(batch?.sessionId == "sess-1")
        #expect(batch?.markSessionTaught == true)
        #expect(batch?.mutations.map(\.status) == ["present", "unexcused", "present"])
        #expect(batch?.state == .pending)
        #expect(box.notices == [.savedLocal])
        #expect(box.enqueued == 1)
        #expect(m.done)
    }

    @Test func whileOfflineSavesLocallyWithoutCallingApi() async {
        net.set(false)
        let m = await model()
        #expect(m.offline)
        #expect(m.label == .saveLocal)
        await m.save()
        #expect(service.syncCalls.isEmpty)
        let stored = await outbox.snapshot().single
        #expect(stored?.idempotencyKey == "id-1")
        #expect(Set(stored?.mutations.map(\.clientMutationId) ?? []) == ["id-2", "id-3", "id-4"])
        #expect(box.notices == [.savedLocal])
    }

    @Test func backWithChangesAsksThenStayOrDiscard() async {
        let m = await model()
        #expect(m.canLeaveFreely)
        m.select("s1", .late)
        #expect(!m.canLeaveFreely)
        m.askDiscard()
        #expect(m.confirmDiscard)
        m.stay()
        #expect(!m.confirmDiscard && !m.done)
        m.askDiscard()
        await m.discard()
        #expect(m.done)
        #expect(service.syncCalls.isEmpty)
    }

    @Test func expiredOrCancelledSessionIsReadOnly() async {
        let expired = await model(attendanceDto(students, status: "taught", canEdit: false))
        #expect(expired.readOnly == .expired)
        expired.toggle("s1")
        #expect(expired.expandedId == nil)
        #expect(!expired.canSave)
        let cancelled = await model(attendanceDto(students, status: "cancelled", canEdit: false))
        #expect(cancelled.readOnly == .cancelled)
    }

    @Test func offlineLoadUsesSnapshotOrFails() async {
        _ = await model() // lần mở có mạng lưu bản chụp
        #expect(await roster.map["sess-1"] != nil)
        service.setGet { throw APIError(kind: .offline) }
        let m = await model(load: false)
        service.setGet { throw APIError(kind: .offline) }
        await m.load()
        #expect(m.rows.count == 3)
        #expect(!m.loadFailed)

        await roster.clear()
        let none = await model(load: false)
        service.setGet { throw APIError(kind: .offline) }
        await none.load()
        #expect(none.loadFailed)
    }

    @Test func snapshotWithRecordedStatusesOpensInEditMode() async {
        _ = await model(attendanceDto([item("s1", "An", "late")], status: "planned"))
        service.setGet { throw APIError(kind: .offline) }
        let m = await model(load: false)
        service.setGet { throw APIError(kind: .offline) }
        await m.load()
        #expect(m.editMode)
    }

    @Test func pendingBatchIsOverlaidAsAlreadySavedSoOnlyNewChangesAreSent() async {
        await outbox.enqueue(makeBatch("k"))
        let m = await model()
        let s1 = m.rows.first { $0.studentId == "s1" }
        #expect(s1?.status == .unexcused)
        #expect(s1?.original == .unexcused)
        #expect(m.editMode)
        #expect(!m.dirty) // không hiện hộp thoại bỏ thay đổi

        m.select("s2", .late)
        service.setSync { _, req in FakeService.ok(req) }
        await m.save()
        #expect(service.syncCalls.last?.body.mutations.map(\.studentId) == ["s2"])
    }

    @Test func failedBatchShowsBannerCodeAndKeepsServerValueAsOriginal() async {
        var failed = makeBatch("k")
        failed.state = .failed
        failed.errorCode = "VALIDATION_ERROR"
        await outbox.enqueue(failed)
        let m = await model()
        #expect(m.failedCode == "VALIDATION_ERROR")
        let s1 = m.rows.first { $0.studentId == "s1" }
        #expect(s1?.status == .unexcused)
        #expect(s1?.original == nil)
    }

    // MARK: Vòng sửa review

    @Test func unknownOwnerFailsClosedAndNeverTouchesOutboxOrApi() async {
        let m = await model(owner: nil)
        #expect(m.rows.count == 3) // vẫn xem được danh sách
        await m.save()
        #expect(m.error == SaveError(code: AttendanceLocalError.ownerMissing, retry: true))
        #expect(!m.done)
        #expect(service.syncCalls.isEmpty)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func engineWithoutOwnerRefusesToWriteEvenIfModelHasOne() async {
        let engineOwner: OutboxOwner? = nil
        let m = AttendanceModel(
            sessionId: "sess-1", service: service, outbox: outbox, roster: roster, device: FixedDevice(),
            engine: makeEngine(service, outbox, roster, owner: engineOwner), connectivity: net, ownerProvider: { testOwner },
            notify: { _ in }, onEnqueued: {}, now: { Date(timeIntervalSince1970: 1_794_221_100) }, timeZone: vn,
            newId: { UUID().uuidString }
        )
        service.setGet { attendanceDto(students) }
        await m.load()
        await m.save()
        #expect(m.error?.code == AttendanceLocalError.ownerMissing)
        #expect(!m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func noResultForOwnBatchFallsBackToReadingOutboxAndStaysLocal() async {
        // Lô chờ cũ của buổi lỗi 5xx chặn buổi này: lô mới không được gửi, nhưng đang nằm trong outbox → lưu trên máy.
        await outbox.enqueue(makeBatch("old"))
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        let m = await model()
        m.select("s2", .late)
        await m.save()
        #expect(box.notices == [.savedLocal])
        #expect(m.done)
        #expect(await outbox.snapshot().count == 2)
    }

    @Test func unreadableOutboxAfterNoResultIsAnErrorNotLocalSave() async {
        let m = await model()
        m.select("s2", .late)
        await outbox.setFailAll(true)
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        await m.save()
        #expect(!m.done)
        #expect(m.error != nil)
        #expect(box.notices.isEmpty)
    }

    @Test func discardRemovesEnqueuedAndSupersededBatchesInsideEngineLock() async {
        let m = await model()
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        await m.save() // lô 1 nằm lại outbox
        m.select("s1", .late) // lô 1 bị thay
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        await m.save() // lô 2
        #expect(await outbox.snapshot().count == 1)
        await m.discard()
        #expect(m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func discardFailureStaysAndReportsInsteadOfClosing() async {
        let m = await model()
        service.setSync { _, _ in throw httpError(500, "INTERNAL_ERROR") }
        await m.save()
        await outbox.setFailRemove(true)
        await m.discard()
        #expect(!m.done)
        #expect(m.error == SaveError(code: AttendanceLocalError.discardFailed, retry: false))
        #expect(!m.saving)
        #expect(await outbox.snapshot().count == 1)

        await outbox.setFailRemove(false)
        await m.discard()
        #expect(m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func cannotLeaveOrDiscardWhileSaving() async {
        let m = await model()
        let gate = AsyncGate()
        service.setSync { _, req in
            await gate.wait()
            return FakeService.ok(req)
        }
        let task = Task { await m.save() }
        while !m.saving { await Task.yield() }
        #expect(!m.canLeaveFreely)
        m.askDiscard()
        #expect(!m.confirmDiscard)
        await m.discard()
        #expect(!m.done)
        await gate.open()
        await task.value
        #expect(m.done)
    }

    @Test func elementRejectedWithOtherCodeFinishesBatchAndNamesTheStudent() async {
        let m = await model()
        service.setSync { _, req in
            FakeService.ok(req, results: req.mutations.map {
                MutationResultDTO(clientMutationId: $0.clientMutationId, studentId: $0.studentId,
                                  result: $0.studentId == "s2" ? "rejected" : "applied", errorCode: $0.studentId == "s2" ? "NOT_FOUND" : nil)
            })
        }
        await m.save()
        #expect(m.done)
        #expect(box.notices == [.saved(parentsToNotify: 0, rejectedNames: ["Lê Chi"])])
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func offlineEnqueueWithoutOwnerReportsOwnerMissingNotGenericError() async {
        net.set(false)
        let m = AttendanceModel(
            sessionId: "sess-1", service: service, outbox: outbox, roster: roster, device: FixedDevice(),
            engine: makeEngine(service, outbox, roster, owner: nil), connectivity: net, ownerProvider: { testOwner },
            notify: { _ in }, onEnqueued: {}, now: { Date(timeIntervalSince1970: 1_794_221_100) }, timeZone: vn, newId: { UUID().uuidString }
        )
        service.setGet { attendanceDto(students) }
        await m.load()
        await m.save()
        #expect(m.error == SaveError(code: AttendanceLocalError.ownerMissing, retry: true))
        #expect(!m.done)
        #expect(await outbox.snapshot().isEmpty)
    }

    @Test func cancelledSaveIsNotReportedAsSavedLocally() async {
        let m = await model()
        service.setSync { _, _ in throw CancellationError() }
        await m.save()
        #expect(box.notices.isEmpty)
        #expect(!m.done)
        #expect(!m.saving)
    }

    @Test func unreadableOutboxOnLoadBlocksSaving() async {
        await outbox.setFailAll(true)
        let m = await model()
        #expect(m.loadFailed)
        #expect(!m.canSave)
    }

    @Test func snapshotPastEditableUntilOpensReadOnly() async {
        var dto = attendanceDto(students, status: "taught")
        dto = SessionAttendanceDTO(sessionId: dto.sessionId, classId: dto.classId, className: dto.className, sessionDate: dto.sessionDate,
                                   startsAt: dto.startsAt, sessionStatus: dto.sessionStatus, canEdit: true,
                                   editableUntil: "2026-11-09T00:00:00Z", items: dto.items) // đã qua so với giờ giả 10:45Z
        _ = await model(dto)
        service.setGet { throw APIError(kind: .offline) }
        let m = await model(load: false)
        service.setGet { throw APIError(kind: .offline) }
        await m.load()
        #expect(m.readOnly == .expired)
        #expect(!m.canSave)
    }
}

final class FailSwitch: @unchecked Sendable {
    private let lock = NSLock()
    private var value = true
    var on: Bool {
        get { lock.withLock { value } }
        set { lock.withLock { value = newValue } }
    }
}