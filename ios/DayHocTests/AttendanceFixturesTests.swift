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
            let results = (c["results"] as! [JSON]).enumerated().map { i, r -> MutationResultDTO in
                let cur = (r["current"] as? JSON).map { CurrentDTO(status: $0["status"] as! String, note: $0["note"] as? String) }
                return MutationResultDTO(clientMutationId: "m\(i)", studentId: mutations[i].studentId, result: r["result"] as! String, current: cur)
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
            let got = mergePending(sessions, pendingIds: Set(c["pendingIds"] as! [String]))
            let want = c["expected"] as! [JSON]
            #expect(got.map(\.id) == want.map { $0["id"] as! String }, Comment(rawValue: c["name"] as! String))
            #expect(got.map { s in kinds.first { $0.value == s.kind }!.key } == want.map { $0["kind"] as! String }, Comment(rawValue: c["name"] as! String))
            #expect(got.map(\.pending) == want.map { $0["pending"] as! Bool }, Comment(rawValue: c["name"] as! String))
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
            #expect(verdictOf(results) == expected, Comment(rawValue: c["name"] as! String))
        }
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
}
