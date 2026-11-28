import Foundation
import Testing
@testable import DayHoc

/// Chạy các ca trong `fixtures/attendance/` (dùng chung với Android, định dạng ở README cùng thư mục).
/// Đọc thẳng từ đường dẫn repo, không chép file vào bundle test.
struct AttendanceFixturesTests {
    private typealias JSON = [String: Any]

    private func fixture(_ name: String) throws -> JSON {
        // ios/DayHocTests/<file> → gốc repo
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appending(path: "fixtures/attendance/\(name)"))
        return try #require(try JSONSerialization.jsonObject(with: data) as? JSON)
    }

    private func cases(_ f: JSON, _ key: String = "cases") -> [JSON] { f[key] as? [JSON] ?? [] }

    private func rows(_ a: Any?) -> [AttRow] {
        (a as? [JSON] ?? []).map {
            AttRow(studentId: $0["studentId"] as! String, name: $0["name"] as! String,
                   status: AttStatus(rawValue: $0["status"] as! String)!,
                   original: ($0["original"] as? String).flatMap(AttStatus.init(rawValue:)),
                   note: $0["note"] as? String, parentReported: $0["parentReported"] as? Bool ?? false)
        }
    }

    @Test func buildsMutations() throws {
        let f = try fixture("mutations.json")
        #expect(!cases(f).isEmpty)
        for c in cases(f) {
            var ids = (c["ids"] as! [String]).makeIterator()
            let got = buildMutations(rows(c["rows"]), editMode: c["editMode"] as! Bool, newId: { ids.next()! }, now: c["now"] as! String)
            let want = (c["expected"] as! [JSON]).map {
                OutboxMutation(clientMutationId: $0["clientMutationId"] as! String, studentId: $0["studentId"] as! String,
                               status: $0["status"] as! String, note: $0["note"] as? String, clientUpdatedAt: $0["clientUpdatedAt"] as! String)
            }
            #expect(got == want, Comment(rawValue: c["name"] as! String))
        }
    }

    @Test func countsNotifyAndLabel() throws {
        let f = try fixture("notify_count.json")
        #expect(!cases(f).isEmpty)
        for c in cases(f) {
            let r = rows(c["rows"])
            let want = c["expected"] as! JSON
            let name = Comment(rawValue: c["name"] as! String)
            #expect(notifyCount(r) == want["notifyCount"] as! Int, name)
            let k = want["counts"] as! JSON
            #expect(countsOf(r) == Counts(present: k["present"] as! Int, absent: k["absent"] as! Int, late: k["late"] as! Int), name)
            let l = want["label"] as! JSON
            let label: SaveLabel = switch l["kind"] as! String {
            case "Save": .save
            case "Notify": .notify(l["n"] as! Int)
            case "SaveChanges": .saveChanges
            default: .saveLocal
            }
            #expect(saveLabel(r, editMode: c["editMode"] as! Bool, offline: c["offline"] as! Bool) == label, name)
        }
    }

    @Test func appliesServerResults() throws {
        let f = try fixture("apply_results.json")
        #expect(!cases(f).isEmpty)
        for c in cases(f) {
            let mutations = (c["mutations"] as! [JSON]).enumerated().map { i, m in
                OutboxMutation(clientMutationId: "m\(i)", studentId: m["studentId"] as! String, status: m["status"] as! String, note: nil, clientUpdatedAt: "t")
            }
            // Ghép theo clientMutationId: forMutation = chỉ số mutation mà phần tử trả lời (mặc định bằng vị trí).
            let results = (c["results"] as! [JSON]).enumerated().map { i, r -> MutationResultDTO in
                let target = r["forMutation"] as? Int ?? i
                let cur = (r["current"] as? JSON).map { CurrentDTO(status: $0["status"] as! String, note: $0["note"] as? String) }
                return MutationResultDTO(clientMutationId: "m\(target)", studentId: mutations[target].studentId, result: r["result"] as! String, current: cur)
            }
            let got = applyResults(rows(c["rows"]), mutations: mutations, results: results)
            let want = c["expected"] as! JSON
            let name = Comment(rawValue: c["name"] as! String)
            #expect(got.staleCount == want["staleCount"] as! Int, name)
            let wantRows = want["rows"] as! [JSON]
            #expect(got.rows.map(\.studentId) == wantRows.map { $0["studentId"] as! String }, name)
            #expect(got.rows.map(\.status.rawValue) == wantRows.map { $0["status"] as! String }, name)
            #expect(got.rows.map { $0.original?.rawValue } == wantRows.map { $0["original"] as? String }, name)
            #expect(got.rows.map(\.note) == wantRows.map { $0["note"] as? String }, name)
        }
    }

    @Test func mergesPendingBatchesIntoSessionCards() throws {
        let f = try fixture("pending_merge.json")
        #expect(!cases(f).isEmpty)
        let kinds: [String: SessionKind] = ["Teaching": .teaching, "Missed": .missed, "Upcoming": .upcoming, "Taught": .taught, "Cancelled": .cancelled]
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        for c in cases(f) {
            let sessions = (c["sessions"] as! [JSON]).map {
                TodaySession(id: $0["id"] as! String, className: "Lớp", start: t, end: t, kind: kinds[$0["kind"] as! String]!,
                             enrolled: nil, location: nil, attended: nil, total: nil)
            }
            let got = mergePending(sessions, pendingIds: Set(c["pendingIds"] as! [String]), failedIds: Set(c["failedIds"] as? [String] ?? []))
            let want = c["expected"] as! [JSON]
            #expect(got.map(\.id) == want.map { $0["id"] as! String }, Comment(rawValue: c["name"] as! String))
            #expect(got.map { s in kinds.first { $0.value == s.kind }!.key } == want.map { $0["kind"] as! String }, Comment(rawValue: c["name"] as! String))
            #expect(got.map(\.pending) == want.map { $0["pending"] as! Bool }, Comment(rawValue: c["name"] as! String))
            #expect(got.map(\.syncFailed) == want.map { $0["syncFailed"] as? Bool ?? false }, Comment(rawValue: c["name"] as! String))
        }
    }

    @Test func keepsOrStopsBatchOnError() throws {
        let f = try fixture("error_decision.json")
        #expect(!cases(f).isEmpty)
        for c in cases(f) {
            let e = c["error"] as! JSON
            let error: APIError = switch e["type"] as! String {
            case "network": APIError(kind: .offline)
            case "timeout": APIError(kind: .timeout)
            case "invalid": APIError(kind: .invalidResponse)
            default: APIError(kind: .http, status: e["httpStatus"] as? Int, code: e["code"] as? String)
            }
            let want = c["expected"] as! JSON
            let expected: ErrorDecision = (want["decision"] as! String) == "keep" ? .keep(countAttempt: want["countAttempt"] as! Bool) : .drop(want["code"] as! String)
            #expect(decideOnError(error, attempts: c["attempts"] as! Int) == expected, Comment(rawValue: c["name"] as! String))
        }
        for c in cases(f, "batch_verdict") {
            let results = (c["results"] as! [JSON]).enumerated().map {
                MutationResultDTO(clientMutationId: "m\($0)", studentId: "s\($0)", result: $1["result"] as! String, errorCode: $1["errorCode"] as? String)
            }
            let want = c["expected"] as! JSON
            let expected: BatchVerdict = (want["verdict"] as! String) == "done" ? .done : .rejected(want["code"] as! String)
            let name = Comment(rawValue: c["name"] as! String)
            #expect(verdictOf(results) == expected, name)
            if let ids = want["rejectedStudentIds"] as? [String] { #expect(rejectedStudentIds(results) == ids, name) }
        }
        let deletable = f["deletable"] as? [JSON] ?? []
        #expect(!deletable.isEmpty)
        for c in deletable { #expect(isDeletableError(c["code"] as! String) == c["expected"] as! Bool, Comment(rawValue: c["code"] as! String)) }
    }

    @Test func sessionSheet() throws {
        let f = try fixture("sheet.json")
        for c in f["sheet_action"] as! [JSON] {
            #expect(sheetAction(status: c["status"] as! String, pending: c["pending"] as! Bool).rawValue == c["expected"] as! String)
        }
        for c in f["repeat_label"] as! [JSON] {
            #expect(repeatLabel(c["weekdays"] as! [Int]) == c["expected"] as? String)
        }
    }

    @Test func flushRules() async throws {
        let f = try fixture("flush_rules.json")
        #expect(!cases(f).isEmpty)
        let defaultOwner = (f["owner"] as? JSON).map { OutboxOwner(userId: $0["userId"] as! String, tenantId: $0["tenantId"] as! String) }
        for c in cases(f) {
            let name = Comment(rawValue: c["name"] as! String)
            let owner: OutboxOwner? = c.keys.contains("owner")
                ? (c["owner"] as? JSON).map { OutboxOwner(userId: $0["userId"] as! String, tenantId: $0["tenantId"] as! String) }
                : defaultOwner
            let batches = (c["batches"] as! [JSON]).enumerated().map { i, b -> OutboxBatch in
                var batch = makeBatch(b["key"] as! String, session: b["sessionId"] as! String, attempts: b["attempts"] as! Int,
                                      owner: OutboxOwner(userId: b["userId"] as! String, tenantId: b["tenantId"] as! String))
                batch.state = OutboxState(rawValue: b["state"] as! String)!
                batch.errorCode = b["errorCode"] as? String
                return batch
            }
            let script = c["script"] as? [String: String] ?? [:]
            let service = FakeService()
            service.setSync { key, req in
                let step = script[key] ?? "ok"
                let parts = step.split(separator: ":", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
                switch parts[0] {
                case "network": throw APIError(kind: .offline)
                case "http": throw APIError(kind: .http, status: Int(parts[1]), code: parts.count > 2 && !parts[2].isEmpty ? parts[2] : nil)
                case "rejected": return FakeService.ok(req, results: FakeService.each(req, "rejected", code: parts[1]))
                default: return FakeService.ok(req)
                }
            }
            let outbox = InMemoryOutbox(batches)
            let roster = InMemoryRoster()
            for s in Set(batches.map(\.sessionId)) { await roster.put(sessionId: s, owner: testOwner, json: "{}") }
            let engine = makeEngine(service, outbox, roster, owner: owner)
            let events = engine.events()

            let report = try await engine.flush()

            let want = c["expected"] as! JSON
            #expect(service.syncCalls.map { $0.key } == want["sent"] as! [String], name)
            #expect(report.result == ((want["result"] as! String) == "done" ? .done : .retry), name)
            let remaining = await outbox.snapshot()
            let wantRemaining = want["remaining"] as! [JSON]
            #expect(remaining.map(\.idempotencyKey) == wantRemaining.map { $0["key"] as! String }, name)
            #expect(remaining.map(\.state.rawValue) == wantRemaining.map { $0["state"] as! String }, name)
            #expect(remaining.map(\.errorCode) == wantRemaining.map { $0["errorCode"] as? String }, name)
            for key in want["attemptsAdded"] as? [String] ?? [] {
                let before = batches.first { $0.idempotencyKey == key }!.attempts
                #expect(remaining.first { $0.idempotencyKey == key }?.attempts == before + 1, name)
            }
            var removed: [String] = []
            for s in Set(batches.map(\.sessionId)) where await roster.get(sessionId: s, owner: testOwner) == nil { removed.append(s) }
            #expect(Set(removed) == Set(want["rosterRemoved"] as! [String]), name)
            var got: [SyncEvent] = []
            let wantEvents = (want["events"] as? [String] ?? []).map { e -> SyncEvent in
                let p = e.split(separator: ":").map(String.init)
                return p[0] == "synced" ? .synced(sessionId: p[1], rejected: Int(p[2].replacingOccurrences(of: "rejected=", with: ""))!) : .dropped(sessionId: p[1], code: p[2])
            }
            if !wantEvents.isEmpty {
                for await e in events { got.append(e); if got.count == wantEvents.count { break } }
            }
            #expect(got == wantEvents, name)
        }
    }
}
