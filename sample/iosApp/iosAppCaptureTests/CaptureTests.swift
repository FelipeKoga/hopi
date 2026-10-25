import Shared
@testable import HopiSample
import XCTest

/// Swift concurrency's URLSession APIs, which Kotlin/Native cannot call, recorded by automatic
/// URLSession capture: each request gives exactly one record, `source = urlsession`. Runs inside
/// the host app (which called `HopiUrlSession.shared.start()`) against the sample's dev server;
/// `sample/iosApp/scripts/capture-swift-test.sh` starts the server and runs this target.
final class CaptureTests: XCTestCase {
    private let base = URL(string: "http://127.0.0.1:8765")!

    override func setUp() {
        super.setUp()
        // Idempotent: the host app started capture already.
        HopiUrlSession.shared.start()
        XCTAssertTrue(HopiUrlSession.shared.isStarted)
    }

    func testDataForRequest() async throws {
        let url = base.appendingPathComponent("items")
        let watch = SampleCaptureProbe.shared.watch(url: url.absoluteString)
        let (data, response) = try await URLSession.shared.data(for: URLRequest(url: url))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        let record = try await self.record(watch)
        XCTAssertEqual(record[0], "urlsession")
        XCTAssertEqual(record[1], "GET")
        XCTAssertEqual(record[2], "200")
        XCTAssertEqual(record[6], String(data.count))
        XCTAssertEqual(record[7], String(data.count), "the whole body is kept below the cap")
    }

    func testDataFromUrl() async throws {
        let url = base.appendingPathComponent("json")
        let watch = SampleCaptureProbe.shared.watch(url: url.absoluteString)
        let (data, _) = try await URLSession.shared.data(from: url)
        let record = try await self.record(watch)
        XCTAssertEqual(record[0], "urlsession")
        XCTAssertEqual(record[6], String(data.count))
    }

    func testUploadForFrom() async throws {
        let url = base.appendingPathComponent("echo")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let body = Data(#"{"swift":"upload(for:from:)"}"#.utf8)
        let watch = SampleCaptureProbe.shared.watch(url: url.absoluteString)
        let (data, _) = try await URLSession.shared.upload(for: request, from: body)
        let record = try await self.record(watch)
        XCTAssertEqual(record[0], "urlsession")
        XCTAssertEqual(record[1], "POST")
        XCTAssertEqual(record[4], String(body.count), "the request body, from the private upload factory")
        XCTAssertEqual(record[5], "-", "captured, not skipped")
        XCTAssertEqual(record[6], String(data.count))
    }

    func testBytesFor() async throws {
        let url = base.appendingPathComponent("chunked")
        let watch = SampleCaptureProbe.shared.watch(url: url.absoluteString)
        let (bytes, _) = try await URLSession.shared.bytes(for: URLRequest(url: url))
        var count = 0
        for try await _ in bytes { count += 1 }
        let record = try await self.record(watch)
        XCTAssertEqual(record[0], "urlsession")
        XCTAssertEqual(record[6], String(count))
        XCTAssertEqual(record[7], String(count))
    }

    func testDownloadFor() async throws {
        let url = base.appendingPathComponent("large")
        let watch = SampleCaptureProbe.shared.watch(url: url.absoluteString)
        let (file, _) = try await URLSession.shared.download(for: URLRequest(url: url))
        let size = try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? Int
        let record = try await self.record(watch)
        XCTAssertEqual(record[0], "urlsession")
        XCTAssertEqual(record[6], size.map(String.init), "the download's size")
        XCTAssertEqual(record[7], "0", "a download keeps no bytes")
        XCTAssertEqual(record[8], "download")
    }

    func testThirdPartySdkShapes() async throws {
        let watches = ["json", "echo", "items", "chunked"].map {
            SampleCaptureProbe.shared.watch(url: base.appendingPathComponent($0).absoluteString)
        }
        ThirdPartySDK.run()
        for watch in watches {
            let record = try await self.record(watch)
            XCTAssertEqual(record[0], "urlsession")
        }
    }

    /// The record's fields, see SampleCaptureWatch.
    private func record(_ watch: SampleCaptureWatch) async throws -> [String] {
        let line: String? = await withCheckedContinuation { continuation in
            watch.await(timeoutMs: 15_000) { continuation.resume(returning: $0) }
        }
        let record = try XCTUnwrap(line, "no record")
        return record.components(separatedBy: "|")
    }
}
