import SwiftUI

/// C18 Skeleton: thanh chữ, thẻ (3 dòng + nút), dòng danh sách (avatar + 2 dòng + giá trị). Màu Fill.
struct DhSkeletonBar: View {
    var width: CGFloat?
    let height: CGFloat
    var radius: CGFloat = 3

    @Environment(\.dhColors) private var colors

    var body: some View {
        RoundedRectangle(cornerRadius: radius)
            .fill(colors.surfaceContainerLow)
            .frame(width: width, height: height)
            .frame(maxWidth: width == nil ? .infinity : nil, alignment: .leading)
    }
}

struct DhSkeletonText: View {
    var body: some View {
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingControl) {
            DhSkeletonBar(height: 12)
            DhSkeletonBar(height: 12)
            DhSkeletonBar(width: 220, height: 12)
        }
    }
}

struct DhSkeletonCard: View {
    var body: some View {
        DhCard {
            DhSkeletonBar(width: 90, height: 9)
            DhSkeletonBar(width: 180, height: 12)
            DhSkeletonBar(width: 130, height: 9)
            DhSkeletonBar(height: 40, radius: 10)
        }
    }
}

struct DhSkeletonRow: View {
    @Environment(\.dhColors) private var colors

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingContent) {
            Circle().fill(colors.surfaceContainerLow).frame(width: 36, height: 36)
            VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingControl) {
                DhSkeletonBar(width: 160, height: 12)
                DhSkeletonBar(width: 110, height: 9)
            }
            Spacer()
            DhSkeletonBar(width: 64, height: 12)
        }
        .padding(.horizontal, TeachHubTokens.Dimens.spacingControl)
    }
}
