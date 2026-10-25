import Foundation

/// Stands in for a third-party SDK the app does not own (analytics, ads, a crash reporter): plain
/// URLSession code that knows nothing about Hopi and imports no Kotlin. Hopi records its requests
/// anyway, because `HopiUrlSession.shared.start()` captures every URLSession task of the process.
///
/// It runs at launch with the `-thirdPartySDK` argument:
/// `xcrun simctl launch booted dev.koga.hopi.sample -thirdPartySDK`.
enum ThirdPartySDK {
    static let baseURL = URL(string: "http://127.0.0.1:8765")!

    /// The three shapes an SDK typically uses: a completion handler on the shared session, a
    /// session with its own delegate, and Swift concurrency.
    static func run() {
        sharedSessionPing()
        DelegateUploader.shared.upload(#"{"event":"app_open","sdk":"third-party"}"#)
        Task { await asyncCalls() }
    }

    /// `URLSession.shared` with a completion handler.
    static func sharedSessionPing() {
        URLSession.shared.dataTask(with: baseURL.appendingPathComponent("json")) { data, response, error in
            log("completion handler: \(status(response)) \(data?.count ?? 0) bytes \(error.map { "\($0)" } ?? "")")
        }.resume()
    }

    /// `data(for:)`, `upload(for:from:)` and `bytes(for:)`.
    static func asyncCalls() async {
        do {
            let (data, response) = try await URLSession.shared.data(for: URLRequest(url: baseURL.appendingPathComponent("items")))
            log("data(for:): \(status(response)) \(data.count) bytes")
            var post = URLRequest(url: baseURL.appendingPathComponent("echo"))
            post.httpMethod = "POST"
            post.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let (echoed, echoResponse) = try await URLSession.shared.upload(for: post, from: Data(#"{"batch":[1,2,3]}"#.utf8))
            log("upload(for:from:): \(status(echoResponse)) \(echoed.count) bytes")
            let (bytes, streamResponse) = try await URLSession.shared.bytes(for: URLRequest(url: baseURL.appendingPathComponent("chunked")))
            var count = 0
            for try await _ in bytes { count += 1 }
            log("bytes(for:): \(status(streamResponse)) \(count) bytes")
        } catch {
            log("async call failed: \(error)")
        }
    }

    static func status(_ response: URLResponse?) -> Int { (response as? HTTPURLResponse)?.statusCode ?? -1 }

    static func log(_ line: String) { print("ThirdPartySDK: \(line)") }
}

/// A session with a custom delegate, the way SDKs that report upload progress build theirs.
final class DelegateUploader: NSObject, URLSessionDataDelegate {
    static let shared = DelegateUploader()

    private lazy var session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
    private var received = Data()

    func upload(_ json: String) {
        var request = URLRequest(url: ThirdPartySDK.baseURL.appendingPathComponent("echo"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        session.uploadTask(with: request, from: Data(json.utf8)).resume()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        received.append(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        ThirdPartySDK.log("delegate session: \(ThirdPartySDK.status(task.response)) \(received.count) bytes \(error.map { "\($0)" } ?? "")")
        received = Data()
    }
}
