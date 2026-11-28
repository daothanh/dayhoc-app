import SwiftUI

struct DhNavItem: Identifiable {
    let id: Int
    let label: LocalizedStringKey
    /// Ảnh template trong Assets: `nav.<icon>` (chưa chọn) và `nav.<icon>-bold` (đang chọn).
    let icon: String
}

/// Bottom Nav dạng viên nổi (Design System, 22/11/2026): thanh bo tròn nổi trên nội dung, nền blur,
/// tab đang chọn là viên lime trượt, rung nhẹ khi đổi tab. Gắn bằng `.safeAreaInset(edge: .bottom)`
/// để danh sách tự chừa đáy.
struct DhBottomNav: View {
    let items: [DhNavItem]
    @Binding var selected: Int

    @Environment(\.dhColors) private var colors
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                let active = item.id == selected
                Button {
                    withAnimation(.easeInOut(duration: 0.22)) { selected = item.id }
                } label: {
                    VStack(spacing: 2) {
                        Image(active ? "nav.\(item.icon)-bold" : "nav.\(item.icon)")
                            .renderingMode(.template)
                            .resizable()
                            .frame(width: 24, height: 24)
                        Text(item.label)
                            .font(.system(size: 11, weight: active ? .bold : .regular))
                            .lineLimit(1)
                    }
                    .foregroundStyle(active ? colors.onPrimary : colors.onSurfaceVariant)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background {
                        if active {
                            Capsule().fill(colors.primary).matchedGeometryEffect(id: "pill", in: pill)
                        }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? [.isSelected] : [])
                .accessibilityIdentifier("nav.\(item.id)")
            }
        }
        .padding(4)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(colors.outlineVariant, lineWidth: 1))
        .shadow(color: .black.opacity(0.1), radius: 12, y: 8)
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
        .padding(.vertical, 8)
        .sensoryFeedback(.selection, trigger: selected)
    }
}

/// Nút phụ trung tính (DS v2 `outlinePrimary`): viền `buttonBorder` 2px, chữ onSurface, cao 48.
/// `small` = cao 40 cho nút nằm trong nội dung; `icon` là ký hiệu SF dẫn trước nhãn (Figma: plus).
struct DhSecondaryButton: View {
    let title: LocalizedStringKey
    var fullWidth = true
    var small = false
    /// Ký hiệu SF dẫn trước nhãn; `nil` thì không có.
    var icon: String?
    /// Định danh cho UI test; `nil` thì không gắn.
    var identifier: String?
    /// Viền đậm (màu chữ) để báo nút đã sẵn sàng; đổi có hoạt ảnh.
    var emphasized = false
    let action: () -> Void

    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.dhColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: TeachHubTokens.Dimens.spacingControl) {
                if let icon {
                    Image(systemName: icon)
                        .font(.system(size: 20))
                }
                Text(title)
                    .dhText(small ? TeachHubTokens.Typo.buttonSmall : TeachHubTokens.Typo.button)
            }
            .frame(maxWidth: fullWidth ? .infinity : nil, minHeight: small ? 40 : 48)
            .padding(.horizontal, small ? TeachHubTokens.Dimens.spacingContent : TeachHubTokens.Dimens.spacingPage)
            .foregroundStyle(isEnabled ? colors.onSurface : colors.onSurfaceVariant)
            .overlay(
                RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.buttonRadius)
                    .stroke(isEnabled ? (emphasized ? colors.onSurface : colors.buttonBorder) : colors.outlineVariant, lineWidth: 2)
                    .animation(.easeOut(duration: 0.2), value: emphasized)
            )
            .contentShape(RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.buttonRadius))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier ?? "")
    }
}
