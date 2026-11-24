import Foundation

/// Lỗi gọi API (spec Mục 5.6). Màn hình đổi sang chuỗi tiếng Việt theo `code`;
/// `message` của server chỉ là dự phòng nên không giữ.
struct APIError: Error, Equatable {
    enum Kind: Equatable {
        /// Mất mạng, không tới được máy chủ.
        case offline
        case timeout
        /// Máy chủ trả mã HTTP lỗi.
        case http
        /// Phản hồi sai hình dạng hợp đồng.
        case invalidResponse
    }

    let kind: Kind
    /// HTTP status; `nil` khi không có phản hồi.
    var status: Int?
    /// `code` trong body lỗi, xem `teachhub-backend/contracts/error_codes.json`.
    var code: String?
    /// Thời gian chờ của 429: `details.retryAfterSec`, không có thì header `Retry-After`.
    var retryAfterSec: Int?
    /// Số lần còn được nhập lại của `OTP_INVALID`: `details.attemptsLeft`.
    var attemptsLeft: Int?
    /// Lỗi theo trường của 422: `{ "endDate": ["…"] }`.
    var fieldErrors: [String: [String]] = [:]
    /// Body lỗi gốc, để feature đọc `details` riêng (vd `candidates` của học viên trùng).
    var body: Data?

    init(kind: Kind, status: Int? = nil, code: String? = nil, retryAfterSec: Int? = nil) {
        self.kind = kind
        self.status = status
        self.code = code
        self.retryAfterSec = retryAfterSec
    }

    /// Lệnh ghi có thể đã tới server (mất mạng, hết giờ, 5xx, server đang xử lý cùng khóa):
    /// thử lại phải giữ nguyên `Idempotency-Key`. Lỗi khác là server đã từ chối hẳn.
    var retryable: Bool {
        switch kind {
        case .offline, .timeout: true
        case .invalidResponse: false
        case .http: (status ?? 0) >= 500 || code == "IDEMPOTENCY_IN_PROGRESS"
        }
    }

    /// Thông báo đầu tiên của server theo trường, nếu có.
    var firstFieldError: String? { fieldErrors.values.lazy.compactMap(\.first).first }

    init(status: Int, body: Data, retryAfterHeader: String?) {
        let parsed = try? JSONDecoder().decode(ErrorBody.self, from: body)
        self.init(
            kind: .http,
            status: status,
            code: parsed?.code,
            retryAfterSec: parsed?.details?.retryAfterSec ?? retryAfterHeader.flatMap { Int($0) }
        )
        attemptsLeft = parsed?.details?.attemptsLeft
        fieldErrors = parsed?.errors ?? [:]
        self.body = body
    }

    init(_ error: URLError) {
        self.init(kind: error.code == .timedOut ? .timeout : .offline)
    }
}

/// `ErrorBody` trong openapi.json; chỉ đọc các trường app dùng.
private struct ErrorBody: Decodable {
    struct Details: Decodable {
        let retryAfterSec: Int?
        /// `OTP_INVALID` trả số lần còn được nhập; server cũ không có thì bỏ qua.
        let attemptsLeft: Int?
    }

    let code: String
    let details: Details?
    let errors: [String: [String]]?
}
