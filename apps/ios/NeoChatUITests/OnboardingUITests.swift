import XCTest

/// Registers a fresh account on the configured server through the real UI.
final class OnboardingUITests: XCTestCase {
    private let app = XCUIApplication()

    override func setUp() {
        continueAfterFailure = false
        guard let server = ProcessInfo.processInfo.environment["NEOCHAT_TEST_SERVER"], !server.isEmpty else { return }
        app.launchEnvironment["NEOCHAT_TEST_SERVER"] = server
        app.launch()
    }

    func testRegisterAndReachChats() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["NEOCHAT_TEST_SERVER"] == nil,"Requires the local API with the test mailbox")
        shot("01-login")
        // Fill the fields in sign-in mode: in registration mode the simulator covers the
        // password field with its own strong password, which typed text cannot replace.
        let user = app.textFields["Никнейм"]
        user.tap()
        let username = "ios_ci_\(Int.random(in: 100000...999999))"
        user.typeText(username)
        let pass = app.secureTextFields["Пароль"]
        pass.tap()
        pass.typeText("correct horse battery")
        app.buttons["Создать аккаунт"].tap()
        XCTAssertTrue(app.buttons["Уже есть аккаунт? Войти"].waitForExistence(timeout: 5))
        let name = app.textFields["Имя"]
        name.tap()
        name.typeText("Тест")
        let email = app.textFields["Электронная почта"]
        app.scrollViews.firstMatch.swipeUp()
        email.tap()
        email.typeText(username+"@example.test")
        app.buttons["Отправить ссылку"].tap()
        let checked = app.buttons["Я подтвердил почту"]
        guard checked.waitForExistence(timeout:30) else {
            shot("02-email-send-failed")
            return XCTFail("Email confirmation action did not appear")
        }
        checked.tap()
        XCTAssertTrue(app.staticTexts["Почта подтверждена"].waitForExistence(timeout:10))
        shot("02-register")
        app.buttons["Создать аккаунт"].tap()
        if !app.staticTexts["Сохраните код восстановления"].waitForExistence(timeout: 30) {
            shot("03-submit-failed")
            XCTFail("recovery page did not appear")
        }
        app.switches.firstMatch.tap()
        app.buttons["Продолжить"].tap()
        if !app.tabBars.buttons["Чаты"].waitForExistence(timeout: 20) {
            shot("04-chats-failed")
            print("APP STATE:", app.state.rawValue)
            XCTFail("chat list did not appear")
        }
        shot("04-chats")
        for filter in ["Группы", "Личные", "Все"] {
            app.buttons[filter].tap()
            XCTAssertTrue(app.buttons[filter].isSelected)
        }
        app.tabBars.buttons["Профиль"].tap()
        XCTAssertTrue(app.buttons["Медиа"].waitForExistence(timeout: 10))
        app.buttons["Файлы"].tap()
        XCTAssertTrue(app.buttons["Файлы"].isSelected)
        app.buttons["Медиа"].tap()
        XCTAssertTrue(app.buttons["Медиа"].isSelected)
        shot("05-profile")
        app.tabBars.buttons["Чаты"].tap()
        app.buttons["Новый чат"].tap()
        app.segmentedControls.buttons["Канал"].tap()
        let channel = app.textFields["Название канала"]
        XCTAssertTrue(channel.waitForExistence(timeout: 5))
        channel.tap()
        channel.typeText("Проверка чатов 062")
        app.buttons["Создать канал"].tap()
        let input = app.textViews["Текст сообщения"]
        guard input.waitForExistence(timeout: 20) else {
            shot("06-open-chat-failed")
            return XCTFail("chat did not open after channel creation")
        }
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

        // Pin and archive through the same selection toolbar available for a batch.
        selectChat()
        app.buttons["Закрепить"].tap()
        XCTAssertTrue(app.buttons["Новый чат"].waitForExistence(timeout: 5))
        selectChat()
        app.buttons["Архивировать"].tap()
        XCTAssertTrue(app.buttons["Новый чат"].waitForExistence(timeout: 5))
        let archive = app.buttons["Архив"]
        XCTAssertFalse(archive.isHittable)
        let table = app.tables.firstMatch
        let shortStart = table.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        shortStart.press(forDuration: 0.05, thenDragTo: shortStart.withOffset(CGVector(dx: 0, dy: 45)))
        XCTAssertFalse(app.buttons["Скрыть архив"].exists, "A short pull must settle closed")
        revealArchive()
        XCTAssertTrue(archive.isHittable)
        shot("07-archive-revealed")
        app.buttons["Скрыть архив"].tap()
        XCTAssertFalse(archive.isHittable)
        revealArchive()
        XCTAssertTrue(archive.isHittable, "Archive must stay tappable after releasing the pull")
        archive.tap()
        XCTAssertTrue(app.buttons["Сортировка архива"].waitForExistence(timeout: 5))
        app.tables.cells.firstMatch.tap()
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        XCTAssertEqual(input.value as? String, "черновик")
        app.buttons["Отправить"].tap()
        let message = app.tables["Сообщения"].cells.matching(NSPredicate(format: "label CONTAINS %@", "черновик")).firstMatch
        XCTAssertTrue(message.waitForExistence(timeout: 10))
        shot("08-chat-opened")

        // Touch a populated cell: the old swipe-back check only covered an empty chat.
        // Repeat after the reply composer changes the layout and opens the keyboard.
        for emptySpace in [false, true] {
          for right in [false, true] {
            let startX = emptySpace ? 0.25 : 0.65
            let endX = emptySpace ? (right ? 0.65 : 0.05) : (right ? 0.95 : 0.25)
            let start = message.coordinate(withNormalizedOffset: CGVector(dx: startX, dy: 0.5))
            start.press(forDuration: 0.05, thenDragTo: message.coordinate(withNormalizedOffset: CGVector(dx: endX, dy: 0.5)), withVelocity: .fast, thenHoldForDuration: 0)
            XCTAssertEqual(app.state, .runningForeground)
            XCTAssertTrue(app.buttons["Отменить ответ"].waitForExistence(timeout: 5))
            app.buttons["Отменить ответ"].tap()
            XCTAssertTrue(input.exists)
            message.tap()
            XCTAssertTrue(app.buttons["Ответить"].waitForExistence(timeout: 5))
            app.buttons["Ответить"].tap()
            XCTAssertTrue(app.buttons["Отменить ответ"].waitForExistence(timeout: 5))
            app.buttons["Отменить ответ"].tap()
            XCTAssertTrue(message.exists)
            XCTAssertTrue(input.exists)
            XCTAssertEqual(app.state, .runningForeground)
          }
        }
        shot("08-message-interactions")

        // A rightward gesture starting at the row's screen edge still goes back.
        let edge = message.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
        edge.press(forDuration: 0.05, thenDragTo: message.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)))
        guard app.buttons["Сортировка архива"].waitForExistence(timeout: 5) else {
            shot("08-edge-back-failed")
            print(app.debugDescription)
            return XCTFail("Edge back did not return to the archive")
        }
        app.tables.cells.firstMatch.tap()
        XCTAssertTrue(message.waitForExistence(timeout: 5))

        // The channel has no other recipients, so this message stays unsent.
        // Quick reactions need a sent message and are checked with a peer on a device.

        // Cancellation keeps history, confirmation clears it while the chat stays open.
        app.buttons["Действия чата"].tap()
        app.buttons["Очистить чат…"].tap()
        XCTAssertTrue(app.alerts["Очистить чат?"].waitForExistence(timeout: 5))
        app.alerts.buttons["Отмена"].tap()
        XCTAssertTrue(message.exists)
        app.buttons["Действия чата"].tap()
        app.buttons["Очистить чат…"].tap()
        app.alerts.buttons["Очистить"].tap()
        let cleared = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"), object: message)
        XCTAssertEqual(XCTWaiter.wait(for: [cleared], timeout: 10), .completed)
        XCTAssertTrue(input.exists)
        shot("09-history-cleared")
    }

    private func selectChat() {
        app.buttons["Выбрать чаты"].tap()
        XCTAssertTrue(app.buttons["Готово"].waitForExistence(timeout: 5))
        app.tables.cells.firstMatch.tap()
    }

    private func revealArchive() {
        let table = app.tables.firstMatch
        // The table extends under the navigation/search bars; start in its content.
        let start = table.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let end = table.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.85))
        start.press(forDuration: 0.05, thenDragTo: end)
        if !app.buttons["Скрыть архив"].waitForExistence(timeout: 5) {
            shot("07-archive-pull-failed")
            XCTFail("archive did not reveal after pulling the chat list")
        }
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
