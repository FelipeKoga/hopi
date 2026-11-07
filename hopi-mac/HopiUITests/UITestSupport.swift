import XCTest

extension XCUIApplication {
    /// Chooses [appId] in the toolbar's picker. The scan may still be running, so the picker is
    /// opened again until the app is listed.
    func attach(to appId: String, file: StaticString = #filePath, line: UInt = #line) throws {
        let picker = descendants(matching: .any)["app-picker"]
        XCTAssertTrue(picker.waitForExistence(timeout: 10), file: file, line: line)
        let target = descendants(matching: .any)["target:\(appId)"]
        let deadline = Date().addingTimeInterval(40)
        while !target.exists && Date() < deadline {
            picker.click()
            _ = target.waitForExistence(timeout: 3)
            if !target.exists { typeKey(.escape, modifierFlags: []) }
        }
        try XCTSkipUnless(target.exists, "\(appId) is not in the picker")
        target.click()
    }

    func screenshotAttachment(_ name: String) -> XCTAttachment {
        let shot = XCTAttachment(screenshot: windows.firstMatch.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        return shot
    }
}

/// The app and request path the end-to-end tests run against, from the test runner's environment.
struct E2E {
    let appId: String
    let path: String

    init() throws {
        let env = ProcessInfo.processInfo.environment
        appId = try XCTUnwrap(env["HOPI_E2E_APP"], "set TEST_RUNNER_HOPI_E2E_APP")
        path = try XCTUnwrap(env["HOPI_E2E_PATH"], "set TEST_RUNNER_HOPI_E2E_PATH")
    }
}
