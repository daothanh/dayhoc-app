import SwiftUI

/// Nút chọn một của DS (Figma Design System): chọn thì nền lime và chấm mực, chưa chọn thì viền outline.
/// Vẽ 20x20; vùng chạm 44 do hàng bao ngoài lo.
struct DhRadio: View {
    let selected: Bool

    @Environment(\.dhColors) private var colors

    var body: some View {
        Canvas { ctx, size in
            let d = min(size.width, size.height)
            let stroke: CGFloat = 1.5
            if selected {
                ctx.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: d, height: d)), with: .color(colors.primary))
                let dot: CGFloat = 8
                ctx.fill(
                    Path(ellipseIn: CGRect(x: (d - dot) / 2, y: (d - dot) / 2, width: dot, height: dot)),
                    with: .color(colors.onSurface)
                )
            } else {
                ctx.stroke(
                    Path(ellipseIn: CGRect(x: stroke / 2, y: stroke / 2, width: d - stroke, height: d - stroke)),
                    with: .color(colors.outline),
                    lineWidth: stroke
                )
            }
        }
        .frame(width: 20, height: 20)
        .accessibilityHidden(true)
    }
}
