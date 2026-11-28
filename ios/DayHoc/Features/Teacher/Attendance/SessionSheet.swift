import SwiftUI

/// [T05a] Bottom sheet Chi tiết buổi (Figma A02). `loadRepeat` trả "T2-T4-T6" từ quy tắc lặp của lớp
/// (`GET /classes/{id}`); không có thì dòng ngày bỏ phần "lặp". Dời / Tạo buổi bù / Hủy buổi là T05b, T05c:
/// `onComingSoon`.
struct SessionSheet: View {
    let session: TodaySession
    let timeZone: TimeZone
    let loadRepeat: @Sendable (String) async -> String?
    let onAttend: () -> Void
    let onDismiss: () -> Void
    let onComingSoon: () -> Void

    @State private var repeatText: String?
    @Environment(\.dhColors) private var colors

    var body: some View {
        let day = "\(formatSheetDay(session.start, timeZone)) · \(formatTimeRange(session.start, session.end, timeZone))"
        VStack(alignment: .leading, spacing: TeachHubTokens.Dimens.spacingContent) {
            HStack {
                Text(verbatim: session.className)
                    .dhText(TeachHubTokens.Typo.h2)
                    .foregroundStyle(colors.onSurface)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onDismiss) {
                    Image("ic_close").renderingMode(.template).resizable().frame(width: 24, height: 24)
                        .foregroundStyle(colors.onSurface)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("session.sheet.close")
            }
            Text(repeatText.map { String(localized: "session.sheet.repeat \(day) \($0)") } ?? day)
                .dhText(TeachHubTokens.Typo.body)
                .foregroundStyle(colors.onSurfaceVariant)
            switch sheetAction(status: session.status, pending: session.pending) {
            case .attend: DhPrimaryButton(title: "today.attendance", action: onAttend)
            case .edit: DhPrimaryButton(title: "session.sheet.edit", action: onAttend)
            case .none: EmptyView()
            }
            chevron("session.sheet.reschedule", String(localized: "session.sheet.rescheduleHint"))
            chevron("session.sheet.makeup", nil)
            chevron("session.sheet.cancel", String(localized: "session.sheet.cancelHint"))
            Text("session.sheet.note").dhText(TeachHubTokens.Typo.caption).foregroundStyle(colors.onSurfaceVariant)
        }
        .padding(.horizontal, TeachHubTokens.Dimens.spacingPage)
        .padding(.top, TeachHubTokens.Dimens.spacingControl)
        .padding(.bottom, TeachHubTokens.Dimens.spacingSection)
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: session.classId) { repeatText = await loadRepeat(session.classId) }
    }

    private func chevron(_ title: LocalizedStringKey, _ subtitle: String?) -> some View {
        DhListRow(title: title, subtitle: subtitle, onTap: onComingSoon) {
            Image("ic_arrow_right").renderingMode(.template).resizable().frame(width: 20, height: 20)
                .foregroundStyle(colors.onSurface)
        }
    }
}
