# Icon ứng dụng

Nguồn: Figma `kOMrSBbbMkgGSBTNnFV72z`, page Design System, component set `Brand/App Icon` (`82:2425`).

| Nền tảng | Node Figma | File trong repo |
|---|---|---|
| iOS 1024 (vuông, không alpha) | `82:2418` | `ios/DayHoc/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png` |
| Android foreground | `82:2420` | `android/app/src/main/res/drawable/ic_launcher_foreground.xml` |
| Android background | `82:2422` | màu `brand_logo` (#BDFF3E) trong `values/colors.xml` |
| Android monochrome | `82:2423` | `android/app/src/main/res/drawable/ic_launcher_monochrome.xml` |

Adaptive icon: `mipmap-anydpi-v26/ic_launcher.xml` và `ic_launcher_round.xml` (minSdk 28 nên không cần PNG legacy). Ba flavor dùng chung icon.

## Xuất lại khi đổi icon
- Android: export SVG của glyph trong `82:2420`/`82:2423` (khung 432px, glyph 330px tại 51,51). Dán `d` của từng `<path>` vào `android:pathData` của vector drawable (giữ `viewport` 432, `translate` 51,51). Màu nền đổi ở `colors.xml`.
- iOS: export `82:2418` PNG 1024×1024, rồi kiểm không có alpha: `sips -g hasAlpha AppIcon-1024.png` phải ra `no` (nếu `yes` thì làm phẳng trên nền #BDFF3E).
