# DayHoc app native

App mobile của TeachHub (thương hiệu DayHoc) viết native từ 23/11/2026, theo ADR `TeachHub-ADR-Native-Mobile.md` (D25) trong repo docs. Android và iOS là hai codebase độc lập. Luật nghiệp vụ nằm ở backend (`teachhub-backend`), app chỉ hiển thị, lưu cache và hàng đợi ngoại tuyến.

| Thư mục | Nội dung |
| --- | --- |
| `android/` | Kotlin + Jetpack Compose, một module `:app`, flavor `development`, `staging`, `production` |
| `ios/` | Swift + SwiftUI, `DayHoc.xcodeproj`, cấu hình `Debug` (dev), `Staging`, `Release` (prod) |
| `tokens/teachhub.tokens.json` | Token DS v2 lấy từ Figma `kOMrSBbbMkgGSBTNnFV72z`, nguồn duy nhất cho màu, bo góc, khoảng cách |
| `tool/gen_tokens.py` | Sinh `TeachHubTokens.kt` và `TeachHubTokens.swift` từ file token |
| `fixtures/` | Ca kiểm thử JSON dùng chung cho outbox và đồng bộ (có từ NA-07) |

App Flutter cũ nằm ở repo `teachhub-app`, đóng băng tại `755626e` và sẽ bị xóa ở NA-13.

## Chạy

Backend local chạy ở cổng 3000 (xem `teachhub-backend`).

```bash
cd android && ./gradlew installDevelopmentDebug
```

Flavor `development` gọi `http://10.0.2.2:3000/v1` (emulator). Với máy thật, chạy `adb reverse tcp:3000 tcp:3000` rồi đổi `API_URL` của flavor sang `http://localhost:3000/v1` trên máy mình, không commit.

```bash
open ios/DayHoc.xcodeproj
```

Cấu hình `Debug` gọi `http://localhost:3000/v1`, chạy được trên simulator.

## Gate trước khi báo xong

```bash
python3 tool/gen_tokens.py --check
```

```bash
cd android && ./gradlew lint testDevelopmentDebugUnitTest assembleDevelopmentDebug
```

```bash
cd ios && xcodebuild test -project DayHoc.xcodeproj -scheme DayHoc -destination 'platform=iOS Simulator,name=iPhone 17'
```

CI chạy cả ba ở `.github/workflows/ci.yml`.

## Quy ước

- Token màu, bo góc, khoảng cách đổi trên Figma thì sửa `tokens/teachhub.tokens.json`, chạy `tool/gen_tokens.py`, commit cả file sinh. Không sửa tay file sinh.
- Màn hình không viết màu, cỡ chữ, khoảng cách, bo góc rời. Component dùng chung nằm ở `core/designsystem` (Android) và `Core/DesignSystem` (iOS).
- Không import chéo giữa các không gian vai trò `teacher`, `parent`, `student`. Thứ dùng chung đặt ở `core`.
- Một feature làm Android trước, iOS theo sau, cùng danh sách màn và cùng ca kiểm thử.
