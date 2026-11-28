import SwiftUI

/// App Bar `Root` (Figma Navigation/App Bar, A01): `leading` (chip vai trò), `title` H2 tuỳ chọn,
/// `trailing` bên phải. Biến thể `Detail` (A03): `onBack` thêm nút quay lại 44 ở đầu, `subtitle` là dòng Caption
/// dưới tiêu đề. Cao 56, nền surface, viền dưới outlineVariant 1.5. Tiêu đề co trước, chip không co.
struct DhAppBar<Leading: View, Trailing: View>: View {
    private let title: Text?
    private let subtitle: String?
    private let onBack: (() -> Void)?
    private let leading: Leading
    private let trailing: Trailing

    init(
        title: LocalizedStringKey?,
        subtitle: String? = nil,
        onBack: (() -> Void)? = nil,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = title.map { Text($0) }
        self.subtitle = subtitle
        self.onBack = onBack
        self.leading = leading()
        self.trailing = trailing()
    }

    /// Tiêu đề là chuỗi động (tên lớp): không tra bảng chuỗi.
    init(
        verbatimTitle: String,
        subtitle: String? = nil,
        onBack: (() -> Void)? = nil,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        title = Text(verbatim: verbatimTitle)
        self.subtitle = subtitle
        self.onBack = onBack
        self.leading = leading()
        self.trailing = trailing()
    }

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
            if let onBack { DhBackButton(action: onBack) }
            leading.fixedSize()
            if let title {
                VStack(alignment: .leading, spacing: 0) {
                    title
                        .dhText(TeachHubTokens.Typo.h2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    if let subtitle {
                        Text(subtitle)
                            .dhText(TeachHubTokens.Typo.caption)
                            .foregroundStyle(colors.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }
                .layoutPriority(-1)
            }
            Spacer(minLength: 0)
            trailing
        }
        .padding(.leading, onBack == nil ? TeachHubTokens.Dimens.spacingPage : TeachHubTokens.Dimens.spacingInline)
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
