# Fixtures điểm danh (T04, NA-07)

Ca kiểm dùng chung cho Android và iOS: cùng đầu vào thì cùng đầu ra. Mỗi file là một nhóm hàm thuần; file có trường `_doc` mô tả luật.
Test Android đọc trực tiếp các file này (`android/app/src/test/.../AttendanceFixturesTest.kt`), iOS nên làm tương tự.

| File | Hàm Android | Đầu vào → đầu ra |
|---|---|---|
| `mutations.json` | `buildMutations` | `rows` + `editMode` + `ids` (cấp tuần tự cho `clientMutationId`) + `now` → danh sách mutation |
| `notify_count.json` | `countsOf`, `notifyCount`, `saveLabel` | `rows` + `editMode` + `offline` → bộ đếm, số phụ huynh được báo, nhãn nút |
| `apply_results.json` | `applyResults` (ghép theo `clientMutationId`) | `rows` + `mutations` + `results` của server → `rows` mới và `staleCount` |
| `pending_merge.json` | `mergePending` | thẻ buổi (`id`, `kind`) + `pendingIds` → `kind`, `pending` sau khi gộp |
| `error_decision.json` | `decideOnError`, `verdictOf`, `rejectedStudentIds`, `isDeletableError` | lỗi gọi API + số lần thử → giữ/bỏ lô; kết quả 200 → xong/bị từ chối |
| `flush_rules.json` | `AttendanceSyncEngine.flush` | lô trong outbox + kịch bản API + chủ → lô đã gửi, lô còn lại (pending/failed), kết quả, bản chụp danh sách bị xóa |
| `roster.json` | `rowsOf`, `lastEditedBy` (iOS: `lastEditorName`) | `items` của GET điểm danh (thiếu trường = phản hồi cũ) → `rows` dựng sẵn (vắng có phép khi PH đã báo), người sửa lần cuối |
| `sheet.json` | `sheetAction`, `repeatLabel` | trạng thái buổi → hành động chính của sheet; thứ ISO → "T2-T4-T6" |

## Định dạng chung

- Hàng học sinh (`rows[]`): `studentId`, `name`, `status`, `original`, `note`, `parentReported`.
  - `status` / `original`: `present` | `late` | `excused` | `unexcused` (đúng `AttendanceStatus` của API). `original` là giá trị đang lưu trên server, `null` = chưa có dòng điểm danh (mặc định hiểu là `present`). `status` là giá trị đang hiển thị.
  - Hàng "đổi" = `status` khác `original ?? present`.
  - `parentReported`: phụ huynh đã báo vắng trước (không tính vào số phụ huynh được báo). `parentReason` (tùy chọn, chỉ `roster.json`): `sick` | `family` | `other`.
- Mutation: `clientMutationId`, `studentId`, `status`, `note`, `clientUpdatedAt` (ISO 8601 có offset, luôn có giây).
- `kind` của thẻ buổi: `Teaching` | `Missed` | `Upcoming` | `Taught` | `Cancelled`.
- Đếm: "Có mặt" chỉ gồm `present` (không gồm `late`); "Vắng" = `excused` + `unexcused`; "Muộn" = `late`.
- Xóa hay failed: chỉ xóa lô với `NOT_FOUND`, `SESSION_CANCELLED`, `ATTENDANCE_EDIT_WINDOW_CLOSED`; mọi lỗi 4xx khác (kể cả 404 không mã = `HTTP_404`) và `MAX_ATTEMPTS` giữ lô ở trạng thái `failed` kèm `errorCode`, chặn các lô sau của cùng buổi. Phần tử `rejected` mã khác `ATTENDANCE_EDIT_WINDOW_CLOSED` không làm hỏng lô (lô xong, báo riêng học sinh).
- Quyết định lỗi: `network`/`timeout`/429/401 giữ lô và không tính lần thử; 5xx, `IDEMPOTENCY_IN_PROGRESS`, body sai định dạng giữ lô và tính lần thử, đủ `MAX_ATTEMPTS` = 10 thì bỏ (`MAX_ATTEMPTS`); lỗi 4xx còn lại bỏ lô kèm mã lỗi.
