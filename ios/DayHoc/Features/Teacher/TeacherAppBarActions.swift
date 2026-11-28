import SwiftUI

/// Phần phải của App Bar `Root` ở mọi tab giáo viên (Figma A01, A10, A18…): chip đồng bộ nét đứt + chuông có chấm
/// khi còn thông báo chưa đọc. Phản ánh lần tải gần nhất của Hôm nay; đổi tab không gọi thêm API.
struct TeacherAppBarActions: View {
    let model: TodayModel
    let onBell: () -> Void

    var body: some View {
        HStack(spacing: TeachHubTokens.Dimens.spacingInline) {
            DhChip(text: syncText, style: .dashed)
            DhBellButton(unread: model.data?.unread ?? 0, label: "today.notifications", action: onBell)
        }
    }

    private var syncText: String {
        if model.offline { return String(localized: "today.sync.offline") }
        if model.syncing { return String(localized: "today.sync.busy") }
        return String(localized: "today.sync.done")
    }
}
