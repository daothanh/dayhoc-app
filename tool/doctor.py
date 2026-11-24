#!/usr/bin/env python3
"""Kiểm tra môi trường phát triển của dayhoc-app. Thoát khác 0 nếu thiếu thứ bắt buộc.

Chạy:   python3 tool/doctor.py

Script không build gì, chỉ soi toolchain để lỗi hiện ra trước khi Gradle hay xcodebuild
báo khó hiểu. Ví dụ thường gặp: JAVA_HOME còn trỏ vào JDK cũ đã bị gỡ, hoặc thiếu
Android SDK Platform 37 mà compileSdk đang cần.
"""
import os
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_MAJOR = "21"  # CI dùng temurin 21, xem .github/workflows/ci.yml
ANDROID_PLATFORM = "android-37"  # khớp compileSdk trong android/app/build.gradle.kts

errors: list[str] = []
warnings: list[str] = []


def ok(label: str) -> None:
    print(f"[ok]       {label}")


def fail(label: str, hint: str) -> None:
    errors.append(label)
    print(f"[lỗi]      {label}")
    print(f"           → {hint}")


def warn(label: str, hint: str) -> None:
    warnings.append(label)
    print(f"[cảnh báo] {label}")
    print(f"           → {hint}")


def run(cmd: list[str]) -> str:
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return f"{out.stdout}\n{out.stderr}"


def check_python() -> None:
    if sys.version_info >= (3, 8):
        ok(f"Python {platform.python_version()}")
    else:
        fail(
            f"Python {platform.python_version()} quá cũ",
            "cần Python 3.8 trở lên để chạy tool/gen_tokens.py",
        )


def check_java() -> None:
    home = os.environ.get("JAVA_HOME", "")
    if not home:
        fail(
            "JAVA_HOME chưa đặt",
            "trỏ vào JDK 21, ví dụ:\n           export JAVA_HOME=$(/usr/libexec/java_home -v 21)",
        )
        return
    if not Path(home).is_dir():
        fail(
            f"JAVA_HOME trỏ vào thư mục không tồn tại: {home}",
            "JDK cũ có thể đã bị gỡ. Trỏ lại vào JDK 21, ví dụ:\n"
            "           export JAVA_HOME=$(/usr/libexec/java_home -v 21)",
        )
        return
    version = run([str(Path(home) / "bin" / "java"), "-version"])
    match = re.search(r'version "(\d+)', version)
    if not match:
        warn(f"Không đọc được phiên bản Java trong {home}", "kiểm lại bằng: java -version")
        return
    major = match.group(1)
    if major == JAVA_MAJOR:
        ok(f"JDK {major} tại {home}")
    else:
        warn(
            f"JDK {major} tại {home}, CI chạy JDK {JAVA_MAJOR}",
            f"dùng JDK {JAVA_MAJOR} để khớp CI",
        )


def check_android() -> None:
    if not (ROOT / "android" / "gradlew").is_file():
        fail("Thiếu android/gradlew", "clone lại repo để có đủ Gradle wrapper")

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    if not sdk:
        fail(
            "Chưa đặt ANDROID_HOME",
            "trỏ vào Android SDK, ví dụ:\n"
            "           export ANDROID_HOME=$HOME/Library/Android/sdk",
        )
        return
    if not Path(sdk).is_dir():
        fail(f"ANDROID_HOME không tồn tại: {sdk}", "kiểm lại đường dẫn SDK")
        return
    ok(f"Android SDK tại {sdk}")

    # SDK mới đặt tên platform kèm số phụ, ví dụ android-37.0, nên khớp theo tiền tố.
    platforms = Path(sdk) / "platforms"
    found = sorted(p.name for p in platforms.glob(f"{ANDROID_PLATFORM}*")) if platforms.is_dir() else []
    if found:
        ok(f"Android SDK Platform đã cài (compileSdk 37): {', '.join(found)}")
    else:
        fail(
            f"Thiếu Android SDK Platform {ANDROID_PLATFORM}",
            f"cài bằng: sdkmanager 'platforms;{ANDROID_PLATFORM}'",
        )

    build_tools = Path(sdk) / "build-tools"
    if build_tools.is_dir() and any(build_tools.iterdir()):
        ok("Build-tools đã cài")
    else:
        warn("Không thấy build-tools", "cài bằng: sdkmanager 'build-tools;<phiên bản>'")


def check_ios() -> None:
    if platform.system() != "Darwin":
        print("[bỏ qua]   Kiểm tra iOS chỉ chạy trên macOS")
        return
    if not shutil.which("xcodebuild"):
        fail(
            "Không tìm thấy xcodebuild",
            "cài Xcode rồi chọn toolchain: sudo xcode-select -s /Applications/Xcode.app",
        )
        return
    version = " ".join(run(["xcodebuild", "-version"]).split())
    ok(f"Xcode: {version}")

    devices = run(["xcrun", "simctl", "list", "devices", "available"])
    found = re.search(r"^\s+(iPhone [^(]+)", devices, re.M)
    if found:
        ok(f"Có iPhone Simulator khả dụng (ví dụ: {found.group(1).strip()})")
    else:
        fail(
            "Không có iPhone Simulator khả dụng",
            "mở Xcode, vào Settings > Components và tải một iOS Simulator runtime",
        )


def main() -> int:
    print("Kiểm tra môi trường dayhoc-app\n")
    check_python()
    check_java()
    check_android()
    check_ios()

    print()
    if errors:
        print(f"Thiếu {len(errors)} mục bắt buộc. Sửa xong chạy lại doctor.")
        return 1
    if warnings:
        print(f"{len(warnings)} mục nên xem lại, nhưng không chặn build.")
    print("Môi trường đủ để chạy gate.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
