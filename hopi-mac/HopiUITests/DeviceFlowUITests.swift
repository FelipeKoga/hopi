import XCTest

/// The viewer against a real app on a device, end to end: it finds the app, connects, lists its
/// requests, and opens one in the inspector with its headers and body.
///
/// Needs a running debug app with Hopi and its traffic. Name them through the test runner:
///   TEST_RUNNER_HOPI_E2E_APP=dev.koga.hopi.sample TEST_RUNNER_HOPI_E2E_PATH=/users \
///     xcodebuild test -project Hopi.xcodeproj -scheme Hopi -destination 'platform=macOS'
/// HOPI_E2E_PATH is text a request's path contains; the test filters the list by it.
final class DeviceFlowUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
    }

    override func tearDown() {
        app.terminate()
    }

    @MainActor
    func testFindsTheAppListsItsRequestsAndOpensOne() throws {
        let env = ProcessInfo.processInfo.environment
        let appId = try XCTUnwrap(env["HOPI_E2E_APP"], "set TEST_RUNNER_HOPI_E2E_APP")
        let path = try XCTUnwrap(env["HOPI_E2E_PATH"], "set TEST_RUNNER_HOPI_E2E_PATH")

        try app.attach(to: appId)

        // Filter the list down to the expected request, so the table stays small.
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        search.click()
        search.typeText(path)

        // A row of the request table (an outline to accessibility, as the sidebar is): its path text.
        let row = app.outlines.staticTexts
            .matching(NSPredicate(format: "value CONTAINS %@ OR label CONTAINS %@", path, path)).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 30), "no request whose path holds \(path)")
        row.click()

        // The inspector fetched the request with get.
        let detailPath = app.staticTexts["detail-path"]
        XCTAssertTrue(detailPath.waitForExistence(timeout: 15), "the inspector did not open")
        XCTAssertTrue((detailPath.value as? String ?? detailPath.label).contains(path),
                      "the inspector shows \(detailPath.label), not \(path)")
        XCTAssertTrue(app.staticTexts["Headers"].exists)

        // Both sides of the exchange show; a response body, when there is one, is rendered.
        app.radioButtons["Request"].click()
        XCTAssertTrue(app.staticTexts["Headers"].waitForExistence(timeout: 5))
        app.radioButtons["Response"].click()
        XCTAssertTrue(app.staticTexts["Body"].waitForExistence(timeout: 5))

        let shot = XCTAttachment(screenshot: app.windows.firstMatch.screenshot())
        shot.name = "inspector-\(appId)"
        shot.lifetime = .keepAlways
        add(shot)
    }
}
