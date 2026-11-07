import XCTest

/// Rules and performance against a real app, end to end. Like [DeviceFlowUITests], needs a running
/// debug app with Hopi, named through TEST_RUNNER_HOPI_E2E_APP and TEST_RUNNER_HOPI_E2E_PATH, and
/// something making that request again while the rule test waits (relaunching the app does).
final class RulesPerfUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDown() {
        app.terminate()
    }

    /// A fail rule added in the viewer fails the app's next request, which the list marks.
    @MainActor
    func testAFailRuleFailsTheNextRequest() throws {
        let e2e = try E2E()
        try app.attach(to: e2e.appId)

        app.typeKey("2", modifierFlags: .command)
        let addRule = app.descendants(matching: .any)["add-rule"]
        XCTAssertTrue(addRule.waitForExistence(timeout: 10))
        try removeEveryRule()
        let removeAll = app.buttons["Remove All"]
        addRule.click()
        app.menuItems["Fail"].click()

        let match = app.textFields["rule-match"]
        XCTAssertTrue(match.waitForExistence(timeout: 5))
        match.click()
        match.typeText("GET \(e2e.path)")
        app.buttons["rule-save"].click()

        XCTAssertTrue(app.staticTexts["fail timeout"].waitForExistence(timeout: 10), "the rule is not listed")
        add(app.screenshotAttachment("rules"))

        // The next request to the path fails, marked with the rule.
        app.typeKey("1", modifierFlags: .command)
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.click()
        search.typeText(e2e.path)
        let marked = app.outlines.staticTexts["fail"]
        XCTAssertTrue(marked.waitForExistence(timeout: 90), "no request to \(e2e.path) failed by the rule")
        add(app.screenshotAttachment("failed-by-rule"))

        // Leave the app as it was.
        app.typeKey("2", modifierFlags: .command)
        XCTAssertTrue(removeAll.waitForExistence(timeout: 5))
        removeAll.click()
        XCTAssertTrue(app.staticTexts["No Rules"].waitForExistence(timeout: 10))
    }

    /// "Mock This Response" makes a mock of a recorded request that the app then serves.
    @MainActor
    func testMockThisResponseServesTheMock() throws {
        let e2e = try E2E()
        try app.attach(to: e2e.appId)
        try removeEveryRule()
        app.typeKey("1", modifierFlags: .command)
        // Only new requests, so the one right-clicked is on screen.
        sleep(2)
        app.typeKey("k", modifierFlags: .command)

        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.click()
        search.typeText(e2e.path)
        let row = app.outlines.staticTexts
            .matching(NSPredicate(format: "value CONTAINS %@ OR label CONTAINS %@", e2e.path, e2e.path)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30))
        row.rightClick()
        app.menuItems["Mock This Response…"].click()

        let status = app.textFields["rule-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 10), "the mock form did not open")
        status.click()
        status.typeText("418")
        app.buttons["rule-save"].click()
        let listed = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'mock 418' OR value BEGINSWITH 'mock 418'")).firstMatch
        XCTAssertTrue(listed.waitForExistence(timeout: 10), "the mock is not listed")

        // The app serves it: a 418 shows up for the path.
        app.typeKey("1", modifierFlags: .command)
        XCTAssertTrue(app.outlines.staticTexts["418"].waitForExistence(timeout: 90), "the app did not serve the mock")
        add(app.screenshotAttachment("mocked"))

        app.typeKey("2", modifierFlags: .command)
        let removeAll = app.buttons["Remove All"]
        XCTAssertTrue(removeAll.waitForExistence(timeout: 5))
        removeAll.click()
        XCTAssertTrue(app.staticTexts["No Rules"].waitForExistence(timeout: 10))
    }

    /// The performance view tails the app: CPU samples arrive and chart.
    @MainActor
    func testPerformanceChartsTheAppsCpu() throws {
        let e2e = try E2E()
        try app.attach(to: e2e.appId)

        app.typeKey("3", modifierFlags: .command)
        let cpu = app.descendants(matching: .any)["monitor:cpu"]
        XCTAssertTrue(cpu.waitForExistence(timeout: 15), "the app lists no CPU monitor")
        let stopped = app.staticTexts["CPU is off"]
        if stopped.exists { cpu.click() }

        // The card shows its unit next to the latest value once a sample arrived.
        XCTAssertTrue(app.staticTexts["%"].waitForExistence(timeout: 30), "no CPU sample arrived")
        sleep(5)
        add(app.screenshotAttachment("performance"))
    }

    /// Opens the rules and removes every one, so a test starts from none.
    private func removeEveryRule() throws {
        app.typeKey("2", modifierFlags: .command)
        let removeAll = app.buttons["Remove All"]
        XCTAssertTrue(removeAll.waitForExistence(timeout: 10))
        if removeAll.isEnabled { removeAll.click() }
        XCTAssertTrue(app.staticTexts["No Rules"].waitForExistence(timeout: 10))
    }
}
