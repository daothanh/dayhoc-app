import SwiftUI

// Khung gốc của hình minh họa trong Figma B01 (node 88:11172), đơn vị point.
private let heroWidth: CGFloat = 358
private let heroHeight: CGFloat = 232
/// Nửa cạnh vòng tròn nhân vật, dùng để suy ra tâm vòng khi vẽ đường nối.
private let heroRingRadius: CGFloat = 27
/// Nét đứt của đường nối nhân vật (Figma: strokeDash 4,4).
private let heroLinkDash: CGFloat = 4
/// Bóng tag nổi (Figma: DS/Elevation/2 · 0 4 12 · 12%).
private let heroTagShadow = Color.black.opacity(0.12)

/// Hình minh họa "Thầy – Trò – Gia đình" của [T01] (Figma B01): ba nhân vật nối nhau trong khung
/// `primaryContainer`, kèm ba nhãn trạng thái. Mọi màu lấy từ token nên tự đổi theo Light/Dark.
///
/// Bố cục giữ đúng tỉ lệ khung 358x232 rồi co giãn theo bề rộng thật của màn.
struct DhHeroClasses: View {
    @Environment(\.dhColors) private var colors

    var body: some View {
        Color.clear
            .aspectRatio(heroWidth / heroHeight, contentMode: .fit)
            .background(colors.primaryContainer)
            .overlay {
                GeometryReader { proxy in
                    let scale = proxy.size.width / heroWidth
                    canvas
                        .frame(width: heroWidth, height: heroHeight, alignment: .topLeading)
                        .scaleEffect(scale, anchor: .topLeading)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.sheetRadius))
    }

    private var canvas: some View {
        ZStack(alignment: .topLeading) {
            // Hai vệt sáng nền, tràn ra ngoài khung nên bị cắt theo bo góc.
            Circle()
                .fill(colors.primary.opacity(0.55))
                .frame(width: 180, height: 180)
                .offset(x: 236, y: -70)
            Circle()
                .fill(colors.primary.opacity(0.35))
                .frame(width: 120, height: 120)
                .offset(x: -46, y: 150)

            // Đường nối tâm ba vòng nhân vật (Figma: Link GV–HS, GV–PH, HS–PH, nét đứt 4,4).
            Canvas { context, _ in
                var path = Path()
                path.move(to: Self.center(152, 51))
                path.addLine(to: Self.center(69, 123))
                path.move(to: Self.center(152, 51))
                path.addLine(to: Self.center(235, 123))
                path.move(to: Self.center(69, 123))
                path.addLine(to: Self.center(235, 123))
                context.stroke(
                    path,
                    with: .color(colors.outline),
                    style: StrokeStyle(lineWidth: 1.5, dash: [heroLinkDash, heroLinkDash])
                )
            }
            .frame(width: heroWidth, height: heroHeight)

            PersonNode(initials: "hero.avatar.teacher", label: "hero.role.teacher")
                .offset(x: 152, y: 51)
            PersonNode(initials: "hero.avatar.student", label: "hero.role.student")
                .offset(x: 69, y: 123)
            PersonNode(initials: "hero.avatar.family", label: "hero.role.family")
                .offset(x: 235, y: 123)

            HeroTag(label: "hero.tag.checked", container: colors.success, icon: "ic_check")
                .offset(x: 14, y: 16)
            HeroTag(label: "hero.tag.invoice", container: colors.onSurface)
                .offset(x: 228, y: 16)
            HeroTag(label: "hero.tag.absent", container: colors.warning)
                .offset(x: 126, y: 200)
        }
        .frame(width: heroWidth, height: heroHeight, alignment: .topLeading)
    }

    /// Tâm vòng nhân vật từ góc trên trái của node (cạnh node + nửa cạnh vòng).
    private static func center(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
        CGPoint(x: x + heroRingRadius, y: y + heroRingRadius)
    }
}

/// Nhân vật: vòng trắng 54 bao avatar 48 chữ viết tắt, nhãn vai trò bên dưới.
private struct PersonNode: View {
    let initials: LocalizedStringKey
    let label: LocalizedStringKey

    @Environment(\.dhColors) private var colors

    var body: some View {
        VStack(spacing: TeachHubTokens.Dimens.spacingInline) {
            ZStack {
                Circle()
                    .fill(colors.surface)
                    .frame(width: 54, height: 54)
                Circle()
                    .fill(colors.surfaceContainerHighest)
                    .overlay(Circle().stroke(colors.outlineVariant, lineWidth: 1.5))
                    .frame(width: 48, height: 48)
                Text(initials)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
            }
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(colors.onSurface)
        }
        .frame(width: 54)
    }
}

/// Nhãn trạng thái nổi trên hình: cao 24, bo tròn hết cỡ, chữ `onInverseSurface`.
private struct HeroTag: View {
    let label: LocalizedStringKey
    let container: Color
    var icon: String?

    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingInline) {
            if let icon {
                Image(icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 12, height: 12)
                    .foregroundStyle(colors.onInverseSurface)
            }
            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(colors.onInverseSurface)
        }
        .padding(.leading, icon != nil ? TeachHubTokens.Dimens.spacingInline : TeachHubTokens.Dimens.spacingControl)
        .padding(.trailing, TeachHubTokens.Dimens.spacingControl)
        .frame(height: 24)
        .background(container, in: Capsule())
        .shadow(color: heroTagShadow, radius: 12, y: 4)
    }
}
