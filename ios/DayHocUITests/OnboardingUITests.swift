import XCTest

/// UI test hermetic cho luồng Đăng nhập → OTP → Chọn vai trò → Onboarding 3 bước.
/// App khởi động bằng launch argument `-uiTestMode <mode>` (xem `UITestSupport.swift`):
/// máy chủ giả chạy trong tiến trình, không gọi backend thật, không chạm Keychain.
/// Mỗi bài tự khởi động lại app với mode riêng nên độc lập, không phụ thuộc thứ tự.
final class OnboardingUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    // MARK: - Đăng nhập

    func testLoginWithInvalidEmailKeepsSendDisabledThenShowsError() {
        let app = launch("login")

        let email = app.textFields["login.email"]
        XCTAssertTrue(email.waitForExistence(timeout: 15), "Không thấy ô email")
        let send = app.buttons["login.sendOtp"]
        XCTAssertTrue(send.exists, "Không thấy nút gửi mã")
        XCTAssertFalse(send.isEnabled, "Nút gửi mã phải tắt khi chưa có email hợp lệ")

        email.tap()
        email.typeText("khong-phai-email")
        XCTAssertFalse(send.isEnabled, "Nút gửi mã phải tắt khi email sai định dạng")

        // Bấm gửi (bàn phím) vẫn báo lỗi ngay tại ô email.
        email.typeText("\n")
        XCTAssertTrue(
            app.staticTexts["login.email.error"].waitForExistence(timeout: 10),
            "Phải hiện lỗi email sai định dạng"
        )
    }

    func testLoginWithValidEmailOpensOtpStep() {
        let app = launch("login")

        let email = app.textFields["login.email"]
        XCTAssertTrue(email.waitForExistence(timeout: 15))
        email.tap()
        email.typeText("co.lan@example.com")

        let send = app.buttons["login.sendOtp"]
        XCTAssertTrue(send.isEnabled, "Nút gửi mã phải bật khi email hợp lệ")
        send.tap()

        XCTAssertTrue(
            app.textFields["otp.code"].waitForExistence(timeout: 15),
            "Gửi mã xong phải sang bước nhập OTP"
        )
    }

    // MARK: - OTP

    func testOtpWrongCodeShowsInlineError() {
        let app = openOtp(mode: "otp-invalid")

        let code = app.textFields["otp.code"]
        code.tap()
        code.typeText("123456")

        XCTAssertTrue(
            app.staticTexts["otp.code.error"].waitForExistence(timeout: 15),
            "OTP sai phải hiện chú thích lỗi ngay dưới ô nhập"
        )
    }

    func testOtpTooManyWrongAttemptsShowsLockBanner() {
        let app = openOtp(mode: "otp-locked")

        let code = app.textFields["otp.code"]
        code.tap()
        code.typeText("123456")

        XCTAssertTrue(
            app.staticTexts["otp.lockedTitle"].waitForExistence(timeout: 15),
            "Bị tạm khóa phải hiện banner cảnh báo"
        )
        XCTAssertFalse(app.buttons["otp.confirm"].isEnabled, "Đang khóa thì nút xác nhận phải tắt")
    }

    // MARK: - Chọn vai trò

    func testRolePickerContinueDisabledUntilRoleChosen() {
        let app = launch("role-picker")

        let continueButton = app.buttons["role.continue"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 15), "Không thấy nút Tiếp tục")
        XCTAssertFalse(continueButton.isEnabled, "Nút Tiếp tục phải tắt khi chưa chọn vai trò")

        let teacher = app.buttons["role.tenant_owner"]
        XCTAssertTrue(teacher.waitForExistence(timeout: 10), "Không thấy thẻ vai trò Giáo viên")
        teacher.tap()

        XCTAssertTrue(waitUntilEnabled(continueButton), "Chọn vai trò xong thì nút Tiếp tục phải bật")
    }

    // MARK: - Onboarding

    /// Đi hết 3 bước: tạo lớp (bắt buộc tên lớp) → thêm học viên → lịch học → vào màn chính giáo viên.
    func testOnboardingClassRequiresNameThenCreatesStudentAndFinishesToTeacherHome() {
        let app = launch("onboarding")

        let className = app.textFields["onboarding.className"]
        XCTAssertTrue(className.waitForExistence(timeout: 15), "Không thấy bước tạo lớp")
        let create = app.buttons["onboarding.continue"]
        XCTAssertTrue(create.exists)
        XCTAssertFalse(create.isEnabled, "Nút Tiếp tục phải tắt khi biểu mẫu còn thiếu")

        chooseMenu(app, id: "onboarding.subject", option: "Toán")
        chooseMenu(app, id: "onboarding.grade", option: "9")

        let amount = app.textFields["onboarding.amount"]
        XCTAssertTrue(amount.exists, "Không thấy ô học phí")
        amount.tap()
        amount.typeText("150000")

        XCTAssertFalse(create.isEnabled, "Chưa có tên lớp thì nút Tiếp tục vẫn phải tắt")

        className.tap()
        className.typeText("Toán 9A")
        XCTAssertTrue(waitUntilEnabled(create), "Đủ trường bắt buộc thì nút Tiếp tục phải bật")
        create.tap()

        // Bước 2: thêm học viên.
        let studentName = app.textFields["onboarding.studentName"]
        XCTAssertTrue(studentName.waitForExistence(timeout: 15), "Không sang được bước thêm học viên")
        let studentsContinue = app.buttons["onboarding.studentsContinue"]
        XCTAssertTrue(studentsContinue.exists)
        XCTAssertFalse(studentsContinue.isEnabled, "Chưa thêm học viên thì nút Tiếp tục phải tắt")

        studentName.tap()
        studentName.typeText("Nguyễn Văn Nam")
        app.buttons["onboarding.addStudent"].tap()

        XCTAssertTrue(waitUntilEnabled(studentsContinue), "Thêm học viên xong thì nút Tiếp tục phải bật")
        studentsContinue.tap()

        // Bước 3: lịch học, mặc định đã hợp lệ; hoàn tất để vào màn chính.
        let finish = app.buttons["onboarding.finishSetup"]
        XCTAssertTrue(finish.waitForExistence(timeout: 15), "Không sang được bước lịch học")
        XCTAssertTrue(waitUntilEnabled(finish), "Lịch hợp lệ thì nút Hoàn tất phải bật")
        finish.tap()

        XCTAssertTrue(
            app.buttons["nav.0"].waitForExistence(timeout: 20),
            "Hoàn tất onboarding phải vào màn chính giáo viên"
        )
    }

    func testOnboardingStudentStepContinueDisabledUntilStudentAdded() {
        let app = launch("onboarding-students")

        let studentsContinue = app.buttons["onboarding.studentsContinue"]
        XCTAssertTrue(studentsContinue.waitForExistence(timeout: 15), "Không thấy bước thêm học viên")
        XCTAssertFalse(studentsContinue.isEnabled, "Chưa thêm học viên thì nút Tiếp tục phải tắt")

        let studentName = app.textFields["onboarding.studentName"]
        XCTAssertTrue(studentName.exists)
        studentName.tap()
        studentName.typeText("Trần Thị Bích")
        app.buttons["onboarding.addStudent"].tap()

        XCTAssertTrue(waitUntilEnabled(studentsContinue), "Thêm học viên xong thì nút Tiếp tục phải bật")
    }

    func testOnboardingScheduleStepFinishesToTeacherHome() {
        let app = launch("onboarding-schedule")

        let finish = app.buttons["onboarding.finishSetup"]
        XCTAssertTrue(finish.waitForExistence(timeout: 15), "Không thấy bước lịch học")
        XCTAssertTrue(waitUntilEnabled(finish), "Lịch mặc định hợp lệ thì nút Hoàn tất phải bật")
        finish.tap()

        XCTAssertTrue(
            app.buttons["nav.0"].waitForExistence(timeout: 20),
            "Hoàn tất lịch học phải vào màn chính giáo viên"
        )
    }

    // MARK: - Tiện ích

    private func launch(_ mode: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["-uiTestMode", mode]
        app.launch()
        return app
    }

    /// Vào tới màn OTP: mở app ở chế độ đăng nhập rồi gửi mã cho email hợp lệ.
    private func openOtp(mode: String) -> XCUIApplication {
        let app = launch(mode)
        let email = app.textFields["login.email"]
        XCTAssertTrue(email.waitForExistence(timeout: 15), "Không thấy màn đăng nhập")
        email.tap()
        email.typeText("co.lan@example.com")
        app.buttons["login.sendOtp"].tap()
        XCTAssertTrue(app.textFields["otp.code"].waitForExistence(timeout: 15), "Không sang bước OTP")
        return app
    }

    /// Chạm ô menu rồi chọn một mục theo nhãn hiển thị.
    private func chooseMenu(_ app: XCUIApplication, id: String, option: String) {
        let menu = app.buttons[id]
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "Không thấy ô chọn \(id)")
        menu.tap()
        let item = app.buttons[option]
        XCTAssertTrue(item.waitForExistence(timeout: 10), "Không thấy lựa chọn \(option)")
        item.tap()
    }

    /// Chờ tới khi phần tử bật (dùng cho nút đổi trạng thái sau lệnh gọi mạng).
    private func waitUntilEnabled(_ element: XCUIElement, timeout: TimeInterval = 15) -> Bool {
        let predicate = NSPredicate(format: "isEnabled == true")
        let expectation = XCTNSPredicateExpectation(predicate: predicate, object: element)
        return XCTWaiter().wait(for: [expectation], timeout: timeout) == .completed
    }
}
