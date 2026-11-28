import SwiftUI

/// C07 Card: Emphasis (nổi, viền mảnh) · Default · Muted (nền Fill). Pressed đổi nền sang Fill.
enum DhCardStyle { case emphasis, `default`, muted }

struct DhCard<Content: View>: View {
    var style: DhCardStyle = .default
    var onTap: (() -> Void)?
    @ViewBuilder let content: () -> Content

    var body: some View {
        if let onTap {
            Button(action: onTap) { DhCardBody(style: style, content: content) }
                .buttonStyle(DhPressableStyle())
        } else {
            DhCardBody(style: style, content: content)
        }
    }
}

private struct DhCardBody<Content: View>: View {
    let style: DhCardStyle
    @ViewBuilder let content: () -> Content

    @Environment(\.dhColors) private var colors
    @Environment(\.isPressedByParentButton) private var pressed

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingControl, content: content)
            .padding(TeachHubTokens.Dimens.spacingContent)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(pressed || style == .muted ? colors.surfaceContainerLow : colors.surface, in: shape)
            .overlay(shape.stroke(style == .emphasis ? colors.surfaceContainerLow : colors.outlineVariant, lineWidth: style == .emphasis ? 1 : 1.5))
            .shadow(color: .black.opacity(style == .emphasis ? 0.06 : 0), radius: 1, y: 1)
            .contentShape(shape)
    }
}

extension EnvironmentValues {
    /// `true` khi nút cha đang được nhấn: thẻ, dòng đổi nền sang Fill (Pressed của DS).
    @Entry var isPressedByParentButton = false
}

/// Kiểu nút trong suốt báo trạng thái Pressed cho nội dung bên trong qua environment.
struct DhPressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.environment(\.isPressedByParentButton, configuration.isPressed)
    }
}
