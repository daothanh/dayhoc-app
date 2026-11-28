import SwiftUI

/// C05 Segmented Control (Figma Selection/Segmented Control): 2–4 ô chia đều, cao 40, viền 1.5.
/// Ô chọn nền lime (`primary`), chữ Button S.
struct DhSegmented: View {
    let options: [String]
    let selectedIndex: Int
    let onSelect: (Int) -> Void

    @Environment(\.dhColors) private var colors

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: TeachHubTokens.Dimens.cardRadius)
        HStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { i, label in
                let selected = i == selectedIndex
                Button { onSelect(i) } label: {
                    Text(label)
                        .dhText(TeachHubTokens.Typo.buttonSmall)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(selected ? colors.onPrimary : colors.onSurface)
                        .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
                        .frame(maxWidth: .infinity, minHeight: 40, maxHeight: 40)
                        .background(selected ? colors.primary : colors.surface)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .frame(height: 40)
        .clipShape(shape)
        .overlay(shape.stroke(colors.outlineVariant, lineWidth: 1.5))
    }
}
