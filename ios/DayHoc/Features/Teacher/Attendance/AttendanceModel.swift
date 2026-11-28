import Foundation
import Observation

/// Thông báo hiện ở T03 (toast) sau khi T04 đóng, hoặc ngay trên T04 khi ở lại.
enum AttendanceNotice: Equatable, Sendable {
    case saved(parentsToNotify: Int)
    case savedLocal
    /// Một số học sinh vừa được sửa trên thiết bị khác; T04 ở lại với bản của server.
    case stale
}

enum ReadOnly: Equatable, Sendable { case expired, cancelled }

/// Lỗi lưu: `code` có thì hiện thông báo riêng; `retry` = có nút "Thử lại" (lô giữ nguyên khóa).
struct SaveError: Equatable, Sendable {
    let code: String?
    let retry: Bool
}

/// [T04] Điểm danh. Một đường gửi duy nhất: lập lô, ghi outbox trước, rồi nhờ SyncEngine gửi và chờ kết quả của buổi
/// này. Lô giữ nguyên `Idempotency-Key` và body khi thử lại; đổi trạng thái thì lô mới.
/// Cùng ca kiểm thử với `AttendanceViewModelTest` bên Android (đã sửa theo thiết kế outbox mới).
@Observable @MainActor
final class AttendanceModel {
    private(set) var loading = true
    private(set) var loadFailed = false
    private(set) var className: String?
    private(set) var startsAt: Date?
    private(set) var rows: [AttRow] = []
    var expandedId: String?
    /// Buổi đã điểm danh (hoặc đã có lô chờ): chỉ gửi học sinh đổi, nút "Lưu thay đổi".
    private(set) var editMode = false
    private(set) var readOnly: ReadOnly?
    private(set) var lastEditedAt: Date?
    private(set) var saving = false
    private(set) var offline = false
    var confirmDiscard = false
    private(set) var error: SaveError?
    /// Mã lỗi của lô đã bị server từ chối vĩnh viễn (đang nằm trong outbox với trạng thái `failed`).
    private(set) var failedCode: String?
    /// T04 xong việc: màn đóng lại về T03.
    private(set) var done = false

    var counts: Counts { countsOf(rows) }
    var dirty: Bool { rows.contains(where: \.changed) }
    var label: SaveLabel { saveLabel(rows, editMode: editMode, offline: offline) }
    /// Lần đầu luôn lưu được (kể cả đủ mặt); chế độ sửa cần có thay đổi; lớp không có học sinh thì không lưu.
    var canSave: Bool { readOnly == nil && !saving && !rows.isEmpty && (!editMode || dirty || failedCode != nil) }
    var canLeaveFreely: Bool { !dirty || readOnly != nil }

    private let sessionId: String
    private let service: any AttendanceService
    private let outbox: any OutboxStore
    private let roster: any RosterCache
    private let device: any DeviceIdProvider
    private let engine: AttendanceSyncEngine
    private let connectivity: any ConnectivityMonitor
    private let owner: OutboxOwner
    private let notify: (AttendanceNotice) -> Void
    private let onEnqueued: () -> Void
    private let now: () -> Date
    private let tz: TimeZone
    private let newId: () -> String
    private let collapseDelay: Duration
    private let sleeper: @Sendable (Duration) async throws -> Void

    /// Lô vừa lưu vào outbox của lần bấm Lưu hiện tại (giữ khóa khi thử lại).
    private var enqueued: OutboxBatch?
    /// Lô đã lập nhưng giáo viên đổi trạng thái sau đó: xóa khi lưu lại.
    private var superseded: [String] = []
    private var collapseTask: Task<Void, Never>?

    init(
        sessionId: String,
        service: any AttendanceService,
        outbox: any OutboxStore,
        roster: any RosterCache,
        device: any DeviceIdProvider,
        engine: AttendanceSyncEngine,
        connectivity: any ConnectivityMonitor,
        owner: OutboxOwner,
        notify: @escaping (AttendanceNotice) -> Void,
        onEnqueued: @escaping () -> Void,
        now: @escaping () -> Date = { .now },
        timeZone: TimeZone = .autoupdatingCurrent,
        newId: @escaping () -> String = { UUID().uuidString.lowercased() },
        collapseDelay: Duration = .milliseconds(300),
        sleeper: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) {
        self.sessionId = sessionId
        self.service = service
        self.outbox = outbox
        self.roster = roster
        self.device = device
        self.engine = engine
        self.connectivity = connectivity
        self.owner = owner
        self.notify = notify
        self.onEnqueued = onEnqueued
        self.now = now
        tz = timeZone
        self.newId = newId
        self.collapseDelay = collapseDelay
        self.sleeper = sleeper
        offline = !connectivity.isOnline
    }

    /// Theo dõi mạng cho tới khi màn đóng (gắn vào `.task` của view).
    func observeConnectivity() async {
        for await online in connectivity.updates() { offline = !online }
    }

    func load() async {
        loading = true
        loadFailed = false
        let dto: SessionAttendanceDTO
        var fromCache = false
        do {
            dto = try await service.get(sessionId: sessionId)
            if let data = try? JSONEncoder().encode(dto) {
                await roster.put(sessionId: sessionId, owner: owner, json: String(decoding: data, as: UTF8.self))
            }
        } catch {
            if Task.isCancelled || error is CancellationError { return }
            // Mất mạng: mở bằng bản chụp lần mở trước (nếu có) để vẫn điểm danh được.
            let kind = (error as? APIError)?.kind
            guard kind == .offline || kind == .timeout,
                  let json = await roster.get(sessionId: sessionId, owner: owner),
                  let cached = try? JSONDecoder().decode(SessionAttendanceDTO.self, from: Data(json.utf8))
            else {
                loading = false
                loadFailed = true
                return
            }
            dto = cached
            fromCache = true
        }
        let batches = ((try? await outbox.all()) ?? []).filter { $0.owner == owner && $0.sessionId == sessionId }
        className = dto.className
        startsAt = parseInstant(dto.startsAt)
        rows = overlayPending(rowsOf(dto.items), batches: batches)
        editMode = dto.sessionStatus == "taught" || batches.contains { $0.state == .pending }
            || (fromCache && dto.items.contains { $0.status != nil })
        readOnly = dto.sessionStatus == "cancelled" ? .cancelled : (!dto.canEdit ? .expired : nil)
        lastEditedAt = lastEditedTime(dto.items)
        failedCode = batches.first { $0.state == .failed }?.errorCode
        loading = false
    }

    func toggle(_ studentId: String) {
        collapseTask?.cancel()
        guard readOnly == nil else { return }
        expandedId = expandedId == studentId ? nil : studentId
    }

    func select(_ studentId: String, _ status: AttStatus) {
        // Nội dung đổi: lô đã lập (đang chờ hoặc lỗi) không còn đúng, lần lưu sau lập lô mới.
        if let b = enqueued { superseded.append(b.idempotencyKey) }
        enqueued = nil
        failedCode = nil
        error = nil
        if let i = rows.firstIndex(where: { $0.studentId == studentId }) { rows[i].status = status }
        collapseTask?.cancel()
        collapseTask = Task { [collapseDelay, sleeper] in
            try? await sleeper(collapseDelay)
            guard !Task.isCancelled, self.expandedId == studentId else { return }
            self.expandedId = nil
        }
    }

    func save() async {
        guard canSave else { return }
        saving = true
        error = nil
        do {
            if failedCode != nil, let batch = enqueued {
                try await outbox.retryFailed(sessionId: sessionId)
                failedCode = nil
                return await submit(batch)
            }
            let batch: OutboxBatch
            if let existing = enqueued {
                batch = existing
            } else {
                try await discardSuperseded()
                batch = buildBatch()
                try await outbox.enqueue(batch)
                enqueued = batch
            }
            if offline { return finishLocal() }
            await submit(batch)
        } catch {
            saving = false
            self.error = SaveError(code: nil, retry: true)
        }
    }

    /// "Gửi lại" lô lỗi đang có trong outbox khi mở T04.
    func resend() async {
        guard failedCode != nil, !saving else { return }
        saving = true
        error = nil
        do {
            try await outbox.retryFailed(sessionId: sessionId)
            let batch = ((try await outbox.all()).filter { $0.owner == owner && $0.sessionId == sessionId && $0.state == .pending }).last
            failedCode = nil
            guard let batch else { saving = false; return }
            enqueued = batch
            await submit(batch)
        } catch {
            saving = false
            self.error = SaveError(code: nil, retry: true)
        }
    }

    private func submit(_ batch: OutboxBatch) async {
        let key = batch.idempotencyKey
        let report = await engine.flush(own: key)
        switch report.outcomes[key] {
        case .synced(let result):
            let applied = applyResults(rows, mutations: batch.mutations, results: result.results)
            enqueued = nil
            if applied.staleCount == 0 {
                notify(.saved(parentsToNotify: result.parentsToNotify))
                saving = false
                done = true
            } else {
                // Server có bản mới hơn cho một số học sinh: hiện bản của server, ở lại để giáo viên xem.
                rows = applied.rows
                editMode = true
                saving = false
                notify(.stale)
            }
        case .kept(network: false):
            saving = false
            error = SaveError(code: nil, retry: true)
        case .failed(let code):
            saving = false
            failedCode = code
            error = SaveError(code: code, retry: false)
        case .deleted(let code):
            enqueued = nil
            saving = false
            if code == "ATTENDANCE_EDIT_WINDOW_CLOSED" { readOnly = .expired } else if code == "SESSION_CANCELLED" { readOnly = .cancelled }
            error = SaveError(code: code, retry: false)
        case .kept(network: true), nil:
            finishLocal()
        }
    }

    private func finishLocal() {
        onEnqueued()
        notify(.savedLocal)
        saving = false
        done = true
    }

    private func buildBatch() -> OutboxBatch {
        let stamp = formatIso(now(), tz)
        return OutboxBatch(
            idempotencyKey: newId(), sessionId: sessionId, owner: owner,
            deviceId: device.deviceId(), deviceTime: stamp,
            mutations: buildMutations(rows, editMode: editMode, newId: newId, now: stamp),
            markSessionTaught: true, createdAt: Int64(now().timeIntervalSince1970 * 1000)
        )
    }

    /// Lô cũ bị thay (đổi trạng thái sau lỗi) và lô lỗi của buổi này: bỏ để không gửi lại nội dung cũ.
    private func discardSuperseded() async throws {
        let failed = try await outbox.all().filter { $0.owner == owner && $0.sessionId == sessionId && $0.state == .failed }
        for key in superseded + failed.map(\.idempotencyKey) { try await outbox.remove(key) }
        superseded = []
    }

    func askDiscard() { confirmDiscard = true }
    func stay() { confirmDiscard = false }

    func discard() async {
        confirmDiscard = false
        if let b = enqueued { try? await outbox.remove(b.idempotencyKey) }
        done = true
    }
}
