import XCTest

/// Registers a fresh account on the configured server through the real UI.
final class OnboardingUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        app.launch()
    }

    func testRegisterAndReachChats() throws {
        shot("01-login")
        // Fill the fields in sign-in mode: in registration mode the simulator covers the
        // password field with its own strong password, which typed text cannot replace.
        let user = app.textFields["Имя пользователя"]
        user.tap()
        user.typeText("ios_ci_\(Int.random(in: 100000...999999))")
        let pass = app.secureTextFields["Пароль"]
        pass.tap()
        pass.typeText("correct horse battery")
        app.buttons["Создать аккаунт"].tap()
        XCTAssertTrue(app.buttons["Уже есть аккаунт? Войти"].waitForExistence(timeout: 5))
        shot("02-register")
        app.buttons["Создать аккаунт"].tap()
        if !app.staticTexts["Сохраните код восстановления"].waitForExistence(timeout: 30) {
            shot("03-submit-failed")
            XCTFail("recovery page did not appear")
        }
        shot("03-recovery")
        app.switches.firstMatch.tap()
        app.buttons["Продолжить"].tap()
        XCTAssertTrue(app.tabBars.buttons["Чаты"].waitForExistence(timeout: 20))
        shot("04-chats")
        app.tabBars.buttons["Профиль"].tap()
        XCTAssertTrue(app.staticTexts["Устройства"].waitForExistence(timeout: 10) || app.buttons["Устройства"].waitForExistence(timeout: 1))
        shot("05-profile")
    }

    /// Writes a PNG next to the host checkout; the simulator shares the host file system.
    private func shot(_ name: String) {
        let att = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        att.name = name
        att.lifetime = .keepAlways
        add(att)
        if let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png"))
        }
    }
}
