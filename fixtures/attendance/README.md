# Fixtures điểm danh (T04, NA-07)

Ca kiểm dùng chung cho Android và iOS: cùng đầu vào thì cùng đầu ra. Mỗi file là một nhóm hàm thuần; file có trường `_doc` mô tả luật.
Test Android đọc trực tiếp các file này (`android/app/src/test/.../AttendanceFixturesTest.kt`), iOS nên làm tương tự.

| File | Hàm Android | Đầu vào → đầu ra |
|---|---|---|
| `mutations.json` | `buildMutations` | `rows` + `editMode` + `ids` (cấp tuần tự cho `clientMutationId`) + `now` → danh sách mutation |
| `notify_count.json` | `countsOf`, `notifyCount`, `saveLabel` | `rows` + `editMode` + `offline` → bộ đếm, số phụ huynh được báo, nhãn nút |
| `apply_results.json` | `applyResults` | `rows` + `mutations` + `results` của server → `rows` mới và `staleCount` |
| `pending_merge.json` | `mergePending` | thẻ buổi (`id`, `kind`) + `pendingIds` → `kind`, `pending` sau khi gộp |
| `error_decision.json` | `decideOnError`, `verdictOf` | lỗi gọi API + số lần thử → giữ/bỏ lô; kết quả 200 → xong/bị từ chối |
| `sheet.json` | `sheetAction`, `repeatLabel` | trạng thái buổi → hành động chính của sheet; thứ ISO → "T2-T4-T6" |

## Định dạng chung

- Hàng học sinh (`rows[]`): `studentId`, `name`, `status`, `original`, `note`, `parentReported`.
  - `status` / `original`: `present` | `late` | `excused` | `unexcused` (đúng `AttendanceStatus` của API). `original` là giá trị đang lưu trên server, `null` = chưa có dòng điểm danh (mặc định hiểu là `present`). `status` là giá trị đang hiển thị.
  - Hàng "đổi" = `status` khác `original ?? present`.
  - `parentReported`: phụ huynh đã báo vắng trước (không tính vào số phụ huynh được báo).
- Mutation: `clientMutationId`, `studentId`, `status`, `note`, `clientUpdatedAt` (ISO 8601 có offset, luôn có giây).
- `kind` của thẻ buổi: `Teaching` | `Missed` | `Upcoming` | `Taught` | `Cancelled`.
- Đếm: "Có mặt" chỉ gồm `present` (không gồm `late`); "Vắng" = `excused` + `unexcused`; "Muộn" = `late`.
- Quyết định lỗi: `network`/`timeout`/429/401 giữ lô và không tính lần thử; 5xx, `IDEMPOTENCY_IN_PROGRESS`, body sai định dạng giữ lô và tính lần thử, đủ `MAX_ATTEMPTS` = 10 thì bỏ (`MAX_ATTEMPTS`); lỗi 4xx còn lại bỏ lô kèm mã lỗi.
