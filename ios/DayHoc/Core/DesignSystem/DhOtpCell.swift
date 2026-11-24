import SwiftUI

/// Trạng thái một ô mã OTP (Figma `Inputs/OTP Cell`, node 24:2343).
enum DhOtpCellState {
    case empty
    case focus
    case filled
    case error
    case locked
}

// Figma `Inputs/OTP Cell`: ô 44×52, bo 12, viền 1.5 (focus/lỗi 2), con trỏ 2×24.
// Cỡ ô chưa có trong token nên khai tại đây, cùng cách DhPrimaryButton khai 48.
private let otpCellWidth: CGFloat = 44
private let otpCellHeight: CGFloat = 52
private let otpCellBorder: CGFloat = 1.5
private let otpCaretWidth: CGFloat = 2
private let otpCaretHeight: CGFloat = 24

/// Ô mã OTP (Figma `Inputs/OTP Cell` 24:2343): 44×52, bo `fieldRadius`, ký tự căn giữa.
///
/// Màu lấy từ token: Empty/Filled viền `outline`, Focus viền `secondary` 2 kèm con trỏ nhấp nháy,
/// Error viền `error` 2, Locked nền `surfaceContainerLow` viền `outlineVariant` chữ `onSurfaceVariant`.
struct DhOtpCell: View {
    let char: Character?
    let state: DhOtpCellState

    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.fieldRadius)
        let borderColor: Color = switch state {
        case .focus: colors.secondary
        case .error: colors.error
        case .locked: colors.outlineVariant
        case .empty, .filled: colors.outline
        }
        let borderWidth = state == .focus || state == .error ? TeachHubTokens.Dimens.focusRingWidth : otpCellBorder
        let background = state == .locked ? colors.surfaceContainerLow : colors.surface
        let textColor: Color = switch state {
        case .error: colors.error
        case .locked: colors.onSurfaceVariant
        default: colors.onSurface
        }

        ZStack {
            if let char {
                // Figma DayHoc/H1: 20/28 SemiBold, giãn chữ -0.015em.
                Text(String(char))
                    .font(.system(size: 20, weight: .semibold))
                    .kerning(-0.3)
                    .foregroundStyle(textColor)
            } else if state == .focus {
                BlinkingCaret(color: colors.onSurface)
            }
        }
        .frame(width: otpCellWidth, height: otpCellHeight)
        .background(background, in: shape)
        .overlay(shape.stroke(borderColor, lineWidth: borderWidth))
    }
}

/// Con trỏ 2×24 của ô đang focus, nhấp nháy 500ms để biết ô đang nhận ký tự.
private struct BlinkingCaret: View {
    let color: Color

    @State private var visible = true

    var body: some View {
        RoundedRectangle(cornerRadius: 1)
            .fill(color)
            .frame(width: otpCaretWidth, height: otpCaretHeight)
            .opacity(visible ? 1 : 0)
            .task {
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(500))
                    withAnimation(.easeInOut(duration: 0.2)) { visible.toggle() }
                }
            }
    }
}
