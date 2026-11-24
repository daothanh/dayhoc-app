import SwiftUI

@main
struct DayHocApp: App {
    /// Khi chạy test, app chỉ làm host: không đọc Keychain, không gọi API thật.
    private static let isTestHost = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    @State private var model: AppModel? = DayHocApp.makeModel()

    var body: some Scene {
        WindowGroup {
            if let model { RootView(model: model) }
        }
    }

    /// UI test (`-uiTestMode <mode>`): khởi động thẳng với máy chủ giả trong tiến trình, xóa trạng thái đã lưu
    /// để mỗi bài test độc lập. Không có launch argument thì giữ nguyên hành vi cũ.
    private static func makeModel() -> AppModel? {
        if let mode = UITestLaunch.mode {
            UITestLaunch.resetPersistedState()
            return AppModel(env: .uiTest(mode: mode))
        }
        return isTestHost ? nil : AppModel(env: .live())
    }
}
