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
        let user = app.textFields["Никнейм"]
        user.tap()
        user.typeText("ios_ci_\(Int.random(in: 100000...999999))")
        let pass = app.secureTextFields["Пароль"]
        pass.tap()
        pass.typeText("correct horse battery")
        app.buttons["Создать аккаунт"].tap()
        XCTAssertTrue(app.buttons["Уже есть аккаунт? Войти"].waitForExistence(timeout: 5))
        let name = app.textFields["Имя"]
        name.tap()
        name.typeText("Тест")
        shot("02-register")
        app.buttons["Создать аккаунт"].tap()
        if !app.staticTexts["Сохраните код восстановления"].waitForExistence(timeout: 30) {
            shot("03-submit-failed")
            XCTFail("recovery page did not appear")
        }
        shot("03-recovery")
        app.switches.firstMatch.tap()
        app.buttons["Продолжить"].tap()
        if !app.tabBars.buttons["Чаты"].waitForExistence(timeout: 20) {
            shot("04-chats-failed")
            print("APP STATE:", app.state.rawValue)
            print(app.debugDescription)
            XCTFail("chat list did not appear")
        }
        shot("04-chats")
        app.tabBars.buttons["Профиль"].tap()
        XCTAssertTrue(app.staticTexts["Устройства"].waitForExistence(timeout: 10) || app.buttons["Устройства"].waitForExistence(timeout: 1))
        shot("05-profile")
        app.tabBars.buttons["Чаты"].tap()
        app.buttons["Новый чат"].tap()
        app.segmentedControls.buttons["Канал"].tap()
        let channel = app.textFields["Название канала"]
        XCTAssertTrue(channel.waitForExistence(timeout: 5))
        channel.tap()
        channel.typeText("Проверка свайпа 061")
        app.buttons["Создать канал"].tap()
        let input = app.textViews["Текст сообщения"]
        XCTAssertTrue(input.waitForExistence(timeout: 20))
        input.tap()
        input.typeText("черновик")
        // Exercise back navigation with the keyboard up, then repeat it after reopening.
        for i in 0..<2 {
            let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.45))
            let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.45))
            start.press(forDuration: 0.05, thenDragTo: end)
            XCTAssertTrue(app.buttons["Новый чат"].waitForExistence(timeout: 5))
            XCTAssertEqual(app.state, .runningForeground)
            if i == 0 {
                app.tables.cells.firstMatch.tap()
                XCTAssertTrue(input.waitForExistence(timeout: 5))
                XCTAssertEqual(input.value as? String, "черновик")
            }
        }
        shot("06-swipe-back")
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
