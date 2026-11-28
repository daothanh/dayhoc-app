import SwiftUI

/// App Bar `Root` (Figma Navigation/App Bar, A01): `leading` (chip vai trò), `title` H2 tuỳ chọn,
/// `trailing` bên phải. Cao 56, nền surface, viền dưới outlineVariant 1.5. Tiêu đề co trước, chip không co.
struct DhAppBar<Leading: View, Trailing: View>: View {
    var title: LocalizedStringKey?
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let trailing: () -> Trailing

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            leading().fixedSize()
            if let title {
                Text(title)
                    .dhText(TeachHubTokens.Typo.h2)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .layoutPriority(-1)
            }
            Spacer(minLength: 0)
            trailing()
        }
        .padding(.leading, TeachHubTokens.Dimens.spacingPage)
        .padding(.trailing, TeachHubTokens.Dimens.spacingInline)
        .frame(height: 56)
        .background(colors.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(colors.outlineVariant).frame(height: 1.5) }
    }
}

extension DhAppBar where Leading == EmptyView {
    /// Không có phần dẫn đầu (onboarding: chỉ tiêu đề và "Bỏ qua").
    init(title: LocalizedStringKey?, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.init(title: title, leading: { EmptyView() }, trailing: trailing)
    }
}
