import SwiftUI

/// C16 Avatar chữ tắt (Figma Display/Avatar): nền Fill, viền 1.5, chữ SemiBold.
struct DhAvatar: View {
    let name: String
    var size: CGFloat = 36

    @Environment(\.dhColors) private var colors

    var body: some View {
        Text(initialsOf(name))
            .dhText(TeachHubTokens.Typo.bodyBold)
            .foregroundStyle(colors.onSurface)
            .lineLimit(1)
            .frame(width: size, height: size)
            .background(colors.surfaceContainerHighest, in: Circle())
            .overlay(Circle().stroke(colors.outlineVariant, lineWidth: 1.5))
            .accessibilityHidden(true)
    }
}

/// Chữ cái đầu của từ đầu và từ cuối ("Nguyễn An" → "NA", "Đỗ Hà" → "ĐH"); một từ thì lấy chữ đầu; rỗng thì "?".
func initialsOf(_ name: String) -> String {
    let words = name.split(whereSeparator: \.isWhitespace)
    guard let first = words.first?.first else { return "?" }
    let last = words.count > 1 ? words.last?.first.map(String.init) ?? "" : ""
    return (String(first) + last).uppercased()
}
