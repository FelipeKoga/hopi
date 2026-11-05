import CryptoKit
import Darwin
import Foundation
import Shared

/// The end-to-end capture check and its measurements, driven by launch arguments so the host can
/// run the same code on a build with Hopi's runtimes and one without and compare them. Plain
/// URLSession, like `ThirdPartySDK`: nothing here knows about Hopi except the rules self-test, which
/// adds its rules through `HopiRules.shared`. Every line goes to stdout as
/// `HopiVerify: {json}` (`xcrun simctl launch --console-pty`).
///
/// - `-verifyCapture`: one request of each URLSession shape, tagged `X-Hopi-Verify: <name>` where the
///   API takes a request, logging status, the bytes the app read, their SHA-256 and the error.
/// - `-captureBench 'n=200;mb=1,5,20;runs=5'`: `n` sequential small requests (per-request time),
///   then `runs` reads of each `mb` size (request plus full read).
/// - `-idleCPU S`: CPU time of every thread of the process over S idle seconds.
/// - `-verifyRules`: the rules self-test on each URLSession shape, through `HopiRules.shared` (the
///   one part of this file that knows Hopi), logging `HopiVerify: {"rules":…}` lines; the shared
///   Kotlin UI runs the same test through Ktor Darwin at the same time. `-verifyRulesHttps <url>`
///   names the HTTPS URL of the pinning check (default `https://example.com/`).
enum CaptureVerification {
    static let base = "http://127.0.0.1:8765"

    static func runFromLaunchArguments() {
        let arguments = ProcessInfo.processInfo.arguments
        func value(_ name: String) -> String? {
            guard let index = arguments.firstIndex(of: name), index + 1 < arguments.count else { return nil }
            return arguments[index + 1]
        }
        if arguments.contains("-verifyCapture") {
            Task.detached { await verify() }
        } else if arguments.contains("-verifyRules") {
            let https = value("-verifyRulesHttps") ?? "https://example.com/"
            Task.detached { await verifyRules(https: https) }
        } else if let spec = value("-captureBench") {
            Task.detached { await bench(spec) }
        } else if let seconds = value("-idleCPU").flatMap(Double.init) {
            Thread.detachNewThread { idleCPU(seconds) }
        }
    }

    // MARK: - Scenarios

    static func verify() async {
        let ephemeral = URLSession(configuration: .ephemeral)
        let delegated = Collector.session(configuration: .ephemeral)
        let partial = PartialDelegate.session()

        await scenario("sw-shared-completion") { tag in
            await completion { URLSession.shared.dataTask(with: request("/json", tag), completionHandler: $0) }
        }
        // The URL-only APIs cannot carry the tag header: the host matches these by their path.
        await scenario("sw-shared-url-completion") { _ in
            await completion { URLSession.shared.dataTask(with: URL(string: base + "/items/2")!, completionHandler: $0) }
        }
        await scenario("sw-ephemeral-404") { tag in
            await completion { ephemeral.dataTask(with: request("/missing", tag), completionHandler: $0) }
        }
        await scenario("sw-delegate-upload") { tag in
            var post = request("/echo", tag)
            post.httpMethod = "POST"
            post.setValue("application/json", forHTTPHeaderField: "Content-Type")
            post.setValue("Bearer sample-secret-6f1d2c", forHTTPHeaderField: "Authorization")
            return await delegated.run(delegated.session.uploadTask(with: post, from: Data(#"{"event":"app_open","n":1}"#.utf8)))
        }
        await scenario("sw-delegate-giant-20-chunked") { tag in
            await delegated.run(delegated.session.dataTask(with: request("/giant?mb=20&chunked=1", tag)))
        }
        await scenario("sw-partial-delegate-large") { tag in
            await partial.run(request("/large", tag))
        }
        await scenario("sw-async-data-for") { tag in
            await asyncCall { try await URLSession.shared.data(for: request("/items", tag)) }
        }
        await scenario("sw-async-data-from") { _ in
            await asyncCall { try await URLSession.shared.data(from: URL(string: base + "/items/3")!) }
        }
        await scenario("sw-async-upload") { tag in
            var post = request("/echo", tag)
            post.httpMethod = "POST"
            post.setValue("application/json", forHTTPHeaderField: "Content-Type")
            return await asyncCall { try await URLSession.shared.upload(for: post, from: Data(#"{"batch":[1,2,3]}"#.utf8)) }
        }
        await scenario("sw-async-bytes") { tag in
            await asyncCall {
                let (bytes, response) = try await URLSession.shared.bytes(for: request("/chunked", tag))
                var data = Data()
                for try await byte in bytes { data.append(byte) }
                return (data, response)
            }
        }
        await scenario("sw-async-upload-1mb") { tag in
            var post = request("/sink", tag)
            post.httpMethod = "POST"
            post.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
            let body = Data((0..<(1024 * 1024)).map { UInt8($0 % 251) })
            return await asyncCall { try await ephemeral.upload(for: post, from: body) }
        }
        await scenario("sw-body-stream") { tag in
            var post = request("/echo", tag)
            post.httpMethod = "POST"
            post.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let body = Data(#"{"streamed":true}"#.utf8)
            post.httpBodyStream = InputStream(data: body)
            post.setValue(String(body.count), forHTTPHeaderField: "Content-Length")
            return await completion { ephemeral.dataTask(with: post, completionHandler: $0) }
        }
        await scenario("sw-download") { tag in
            await withCheckedContinuation { continuation in
                ephemeral.downloadTask(with: request("/large", tag)) { url, response, error in
                    var outcome = CaptureOutcome(status: status(response), error: error, finalURL: response?.url?.absoluteString)
                    if let url, let data = try? Data(contentsOf: url) { outcome.data = data }
                    continuation.resume(returning: outcome)
                }.resume()
            }
        }
        await scenario("sw-redirect") { tag in
            await asyncCall { try await URLSession.shared.data(for: request("/redirect?to=/json", tag)) }
        }
        await scenario("sw-giant-20") { tag in
            await asyncCall { try await ephemeral.data(for: request("/giant?mb=20", tag)) }
        }
        await scenario("sw-refused") { tag in
            await completion { ephemeral.dataTask(with: request("/json", tag, base: "http://127.0.0.1:9"), completionHandler: $0) }
        }
        await scenario("sw-timeout") { tag in
            var slow = request("/slow?s=30", tag)
            slow.timeoutInterval = 2
            return await completion { ephemeral.dataTask(with: slow, completionHandler: $0) }
        }
        await scenario("sw-cancel-before-bytes") { tag in
            await completion(cancelAfter: 0) { ephemeral.dataTask(with: request("/slow?s=30", tag), completionHandler: $0) }
        }
        await scenario("sw-cancel-mid-body") { tag in
            await completion(cancelAfter: 1.5) { ephemeral.dataTask(with: request("/slow?s=30", tag), completionHandler: $0) }
        }
        log(["verify": "done", "engine": "urlsession"])
    }

    // MARK: - Rules

    /// Each rules scenario on each URLSession shape (completion handler on `shared`, a full
    /// delegate, async `data(for:)`, a download task), every rule on its own `rv` query key so no
    /// two scenarios, and no rule of the host's, share a request. A mock or fail never reaches the
    /// dev server; an edit arrives edited; a response edit is unsupported on URLSession, so the
    /// rule says so and the app reads the real response. Delays are tested from Kotlin (a Kotlin
    /// `Duration` is no Swift type). Every rule added here is removed again.
    static func verifyRules(https: String) async {
        let delegated = Collector.session(configuration: .ephemeral)
        let ephemeral = URLSession(configuration: .ephemeral)
        let shapes: [(String, (URLRequest) async -> CaptureOutcome)] = [
            ("shared-completion", { request in await completion { URLSession.shared.dataTask(with: request, completionHandler: $0) } }),
            ("delegate", { request in await delegated.run(delegated.session.dataTask(with: request)) }),
            ("async", { request in await asyncCall { try await URLSession.shared.data(for: request) } }),
            ("download", { request in
                await withCheckedContinuation { continuation in
                    ephemeral.downloadTask(with: request) { url, response, error in
                        var outcome = CaptureOutcome(status: status(response), error: error, finalURL: response?.url?.absoluteString)
                        if let url, let data = try? Data(contentsOf: url) { outcome.data = data }
                        continuation.resume(returning: outcome)
                    }.resume()
                }
            }),
        ]
        var failed = 0
        for (shape, send) in shapes {
            for scenario in rulesScenarios {
                let key = "sw-\(shape)-\(scenario.name)"
                var ids: [Int64] = []
                let started = ContinuousClock.now
                let (ok, outcome, detail) = await scenario.run(key, { ids.append($0) }, send)
                ids.forEach { _ = HopiRules.shared.remove(id: $0) }
                if !ok { failed += 1 }
                var line: [String: Any] = [
                    "rules": scenario.name, "target": shape, "source": "UrlSession", "ok": ok, "status": outcome.status,
                    "body": String(String(decoding: outcome.data, as: UTF8.self).prefix(120)), "ms": milliseconds(ContinuousClock.now - started),
                ]
                if let error = outcome.error as NSError? { line["error"] = "\(error.domain) \(error.code): \(error.localizedDescription)" }
                if let detail { line["detail"] = detail }
                log(line)
            }
        }
        if !(await pinnedRules(https: https)) { failed += 1 }
        log(["rules": "done", "engine": "urlsession", "failed": failed])
    }

    private struct RulesScenario {
        let name: String
        let run: (String, (Int64) -> Void, (URLRequest) async -> CaptureOutcome) async -> (Bool, CaptureOutcome, String?)
    }

    private static let mockBody = #"{"mocked":"by hopi"}"#

    private static let rulesScenarios: [RulesScenario] = [
        RulesScenario(name: "mock") { key, added, send in
            added(HopiRules.shared.mock(match: "GET */json?rv=\(key)") { mock in
                mock.status = KotlinInt(int: 418)
                mock.header(name: "Content-Type", value: "application/json")
                mock.body(text: CaptureVerification.mockBody)
            })
            let outcome = await send(CaptureVerification.request("/json?rv=\(key)", key))
            return (outcome.status == 418 && String(decoding: outcome.data, as: UTF8.self) == CaptureVerification.mockBody, outcome, nil)
        },
        RulesScenario(name: "fail-timeout") { key, added, send in
            added(HopiRules.shared.fail(match: "GET */json?rv=\(key)", failure: .timeout) { _ in })
            let outcome = await send(CaptureVerification.request("/json?rv=\(key)", key))
            return ((outcome.error as NSError?)?.code == NSURLErrorTimedOut, outcome, nil)
        },
        RulesScenario(name: "fail-unknown-host") { key, added, send in
            added(HopiRules.shared.fail(match: "GET */json?rv=\(key)", failure: .unknownhost) { _ in })
            let outcome = await send(CaptureVerification.request("/json?rv=\(key)", key))
            return ((outcome.error as NSError?)?.code == NSURLErrorCannotFindHost, outcome, nil)
        },
        RulesScenario(name: "edit-request") { key, added, send in
            // /missing is a 404; the edit sends it to /echo with a header the echo shows.
            added(HopiRules.shared.edit(match: "GET */missing?rv=\(key)") { edit in
                edit.request { request in
                    request.path = "/echo"
                    request.setHeader(name: "X-Hopi-Rule", value: "edited")
                }
            })
            let outcome = await send(CaptureVerification.request("/missing?rv=\(key)", key))
            let body = String(decoding: outcome.data, as: UTF8.self)
            return (outcome.status == 200 && body.contains("\"/echo?rv=\(key)\"") && body.lowercased().contains("x-hopi-rule"), outcome, nil)
        },
        RulesScenario(name: "edit-response") { key, added, send in
            let id = HopiRules.shared.edit(match: "GET */json?rv=\(key)") { edit in
                edit.response { response in response.status = KotlinInt(int: 203) }
            }
            added(id)
            let unsupported = HopiRules.shared.list().first { $0.id == id }?.unsupported ?? []
            let outcome = await send(CaptureVerification.request("/json?rv=\(key)", key))
            // Unsupported on URLSession: the rule says so and the app reads the real response.
            return (outcome.status == 200 && !unsupported.isEmpty, outcome, "unsupported: \(unsupported)")
        },
    ]

    /// A session whose delegate rejects every server-trust challenge, as an app that pins its
    /// backend to a key no server has: the request fails without a rule and still fails under a
    /// request edit (a rule never answers a challenge), and only a mock, which never connects,
    /// gets through.
    private static func pinnedRules(https: String) async -> Bool {
        let pinned = Collector.session(configuration: .ephemeral, rejectTrust: true)
        guard let host = URL(string: https)?.host else { return false }
        let match = "GET https://\(host)/**"
        let plain = await pinned.run(pinned.session.dataTask(with: URLRequest(url: URL(string: https)!)))
        let editId = HopiRules.shared.edit(match: match) { edit in
            edit.request { $0.setHeader(name: "X-Hopi-Rule", value: "edited") }
        }
        let edited = await pinned.run(pinned.session.dataTask(with: URLRequest(url: URL(string: https)!)))
        _ = HopiRules.shared.remove(id: editId)
        let mockId = HopiRules.shared.mock(match: match) { mock in
            mock.status = KotlinInt(int: 200)
            mock.body(text: "pinned host, mocked")
        }
        let mocked = await pinned.run(pinned.session.dataTask(with: URLRequest(url: URL(string: https)!)))
        _ = HopiRules.shared.remove(id: mockId)
        func seen(_ outcome: CaptureOutcome) -> String {
            (outcome.error as NSError?).map { "\($0.domain) \($0.code)" } ?? "\(outcome.status)"
        }
        let cancelled = { (outcome: CaptureOutcome) in (outcome.error as NSError?)?.code == NSURLErrorCancelled }
        let ok = cancelled(plain) && cancelled(edited) && mocked.status == 200
        log(["rules": "pinned", "ok": ok, "plain": seen(plain), "edited": seen(edited), "mocked": seen(mocked)])
        return ok
    }

    private static func request(_ path: String, _ tag: String, base: String = base) -> URLRequest {
        var request = URLRequest(url: URL(string: base + path)!)
        request.setValue(tag, forHTTPHeaderField: "X-Hopi-Verify")
        return request
    }

    private static func scenario(_ name: String, _ body: (String) async -> CaptureOutcome) async {
        let started = ContinuousClock.now
        let outcome = await body(name)
        var line: [String: Any] = [
            "verify": name, "engine": "urlsession", "status": outcome.status, "bytes": outcome.data.count,
            "sha256": sha256(outcome.data), "ms": milliseconds(ContinuousClock.now - started),
        ]
        if let error = outcome.error as NSError? { line["error"] = "\(error.domain) \(error.code)" }
        if let finalURL = outcome.finalURL { line["finalUrl"] = finalURL }
        log(line)
    }

    private static func completion(
        cancelAfter: Double? = nil,
        _ make: (@escaping @Sendable (Data?, URLResponse?, Error?) -> Void) -> URLSessionDataTask
    ) async -> CaptureOutcome {
        await withCheckedContinuation { continuation in
            let task = make { data, response, error in
                continuation.resume(returning: CaptureOutcome(status: status(response), data: data ?? Data(), error: error, finalURL: response?.url?.absoluteString))
            }
            task.resume()
            if let cancelAfter {
                if cancelAfter == 0 {
                    task.cancel()
                } else {
                    DispatchQueue.global().asyncAfter(deadline: .now() + cancelAfter) { task.cancel() }
                }
            }
        }
    }

    private static func asyncCall(_ call: () async throws -> (Data, URLResponse)) async -> CaptureOutcome {
        do {
            let (data, response) = try await call()
            return CaptureOutcome(status: status(response), data: data, finalURL: response.url?.absoluteString)
        } catch {
            return CaptureOutcome(error: error)
        }
    }

    // MARK: - Measurements

    static func bench(_ spec: String) async {
        var fields: [String: String] = [:]
        for part in spec.split(separator: ";") {
            let pair = part.split(separator: "=", maxSplits: 1).map(String.init)
            if pair.count == 2 { fields[pair[0]] = pair[1] }
        }
        let count = fields["n"].flatMap(Int.init) ?? 200
        let sizes = (fields["mb"] ?? "1,5,20").split(separator: ",").compactMap { Int($0) }
        let runs = fields["runs"].flatMap(Int.init) ?? 5
        let session = URLSession(configuration: .ephemeral)
        let small = URLRequest(url: URL(string: base + "/json")!)
        func once(_ request: URLRequest) async -> (Double, Int) {
            let started = ContinuousClock.now
            let outcome = await completion { session.dataTask(with: request, completionHandler: $0) }
            return (milliseconds(ContinuousClock.now - started), outcome.data.count)
        }
        for _ in 0..<20 { _ = await once(small) }
        var times: [Double] = []
        let cpuStart = processCPU()
        for _ in 0..<count { times.append(await once(small).0) }
        // Process CPU (every thread: the caller, CFNetwork, Hopi's queue and log) per request.
        log(["bench": "small", "n": count, "medianMs": median(times), "p90Ms": percentile(times, 0.9),
             "minMs": times.min() ?? -1, "processCpuUsPerRequest": ((processCPU() - cpuStart) * 1000 / Double(count)).rounded(toPlaces: 1)])
        for mb in sizes {
            let giant = URLRequest(url: URL(string: base + "/giant?mb=\(mb)")!)
            _ = await once(giant)
            var giantTimes: [Double] = []
            var bytes = 0
            let cpuStart = processCPU()
            for _ in 0..<runs {
                let (ms, count) = await once(giant)
                giantTimes.append(ms)
                bytes = count
            }
            log(["bench": "giant", "mb": mb, "runs": runs, "bytes": bytes, "medianMs": median(giantTimes),
                 "minMs": giantTimes.min() ?? -1, "maxMs": giantTimes.max() ?? -1, "all": giantTimes,
                 "processCpuMsPerRun": ((processCPU() - cpuStart) / Double(runs)).rounded(toPlaces: 2)])
        }
        log(["bench": "done"])
    }

    static func idleCPU(_ seconds: Double) {
        Thread.sleep(forTimeInterval: 5)
        let before = threadTimes()
        let cpuStart = processCPU()
        log(["idle": "start", "seconds": seconds, "threads": before.count])
        Thread.sleep(forTimeInterval: seconds)
        let cpuEnd = processCPU()
        let after = threadTimes()
        var rows: [[String: Any]] = []
        for (id, entry) in after {
            let delta = entry.cpuUs - (before[id]?.cpuUs ?? 0)
            if delta > 0 || entry.name.hasPrefix("hopi") {
                rows.append(["thread": id, "name": entry.name, "cpuUs": delta, "new": before[id] == nil])
            }
        }
        // The process's own counter (getrusage), not a sum over the threads alive at each end: a
        // thread that exits inside the window would take its whole lifetime off such a sum.
        let total = Int(((cpuEnd - cpuStart) * 1000).rounded())
        // Threads that exited inside the window are missing here, so this can be below `total`.
        let rowsSum = rows.reduce(0) { $0 + ($1["cpuUs"] as? Int ?? 0) }
        let hopi = after.filter { $0.value.name.hasPrefix("hopi") }.map { $0.value.name }.sorted()
        log(["idle": "done", "seconds": seconds, "threads": after.count, "hopiThreads": hopi,
             "processCpuUsDelta": total, "liveThreadsCpuUs": rowsSum, "rows": rows])
    }

    /// Thread id to (name, user + system CPU in µs), for every thread of this process.
    private static func threadTimes() -> [UInt64: (name: String, cpuUs: Int)] {
        var list: thread_act_array_t?
        var count: mach_msg_type_number_t = 0
        guard task_threads(mach_task_self_, &list, &count) == KERN_SUCCESS, let list else { return [:] }
        defer {
            for index in 0..<Int(count) { mach_port_deallocate(mach_task_self_, list[index]) }
            vm_deallocate(mach_task_self_, vm_address_t(UInt(bitPattern: list)), vm_size_t(Int(count) * MemoryLayout<thread_t>.stride))
        }
        var result: [UInt64: (name: String, cpuUs: Int)] = [:]
        for index in 0..<Int(count) {
            let thread = list[index]
            var basic = thread_basic_info()
            var basicCount = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<integer_t>.size)
            let basicStatus = withUnsafeMutablePointer(to: &basic) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(basicCount)) { thread_info(thread, thread_flavor_t(THREAD_BASIC_INFO), $0, &basicCount) }
            }
            var identifier = thread_identifier_info()
            var identifierCount = mach_msg_type_number_t(MemoryLayout<thread_identifier_info>.size / MemoryLayout<integer_t>.size)
            let identifierStatus = withUnsafeMutablePointer(to: &identifier) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(identifierCount)) { thread_info(thread, thread_flavor_t(THREAD_IDENTIFIER_INFO), $0, &identifierCount) }
            }
            guard basicStatus == KERN_SUCCESS, identifierStatus == KERN_SUCCESS else { continue }
            var name = [CChar](repeating: 0, count: 128)
            if let pthread = pthread_from_mach_thread_np(thread) { pthread_getname_np(pthread, &name, name.count) }
            let cpuUs = Int(basic.user_time.seconds + basic.system_time.seconds) * 1_000_000
                + Int(basic.user_time.microseconds + basic.system_time.microseconds)
            result[identifier.thread_id] = (String(cString: name), cpuUs)
        }
        return result
    }

    /// User plus system CPU of the whole process, in ms.
    private static func processCPU() -> Double {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        let user = Double(usage.ru_utime.tv_sec) * 1000 + Double(usage.ru_utime.tv_usec) / 1000
        let system = Double(usage.ru_stime.tv_sec) * 1000 + Double(usage.ru_stime.tv_usec) / 1000
        return user + system
    }

    // MARK: - Helpers

    static func status(_ response: URLResponse?) -> Int { (response as? HTTPURLResponse)?.statusCode ?? -1 }

    private static func sha256(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    private static func milliseconds(_ duration: Duration) -> Double {
        let (seconds, attoseconds) = duration.components
        return (Double(seconds) * 1000 + Double(attoseconds) / 1e15).rounded(toPlaces: 3)
    }

    private static func median(_ values: [Double]) -> Double { percentile(values, 0.5) }

    private static func percentile(_ values: [Double], _ p: Double) -> Double {
        guard !values.isEmpty else { return -1 }
        let sorted = values.sorted()
        return sorted[min(Int(Double(sorted.count) * p), sorted.count - 1)]
    }

    private static let output = NSLock()

    static func log(_ fields: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else { return }
        output.lock()
        print("HopiVerify: \(text)")
        fflush(stdout)
        output.unlock()
    }
}

/// What one scenario observed: status, the bytes the app read, the error, the response's URL.
private struct CaptureOutcome {
    var status = -1
    var data = Data()
    var error: Error?
    var finalURL: String?

    init(status: Int = -1, data: Data = Data(), error: Error? = nil, finalURL: String? = nil) {
        self.status = status
        self.data = data
        self.error = error
        self.finalURL = finalURL
    }

    init(task: URLSessionTask, data: Data) {
        self.init(status: CaptureVerification.status(task.response), data: data, error: task.error, finalURL: task.response?.url?.absoluteString)
    }

    init(response: URLResponse?, data: Data, error: Error?) {
        self.init(status: CaptureVerification.status(response), data: data, error: error, finalURL: response?.url?.absoluteString)
    }
}

private extension Double {
    func rounded(toPlaces places: Int) -> Double {
        let scale = pow(10, Double(places))
        return (self * scale).rounded() / scale
    }
}

/// A session with a full data delegate that collects the body itself, the way SDKs that stream do.
private final class Collector: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private(set) var session: URLSession!
    private var received = Data()
    private var continuation: CheckedContinuation<Data, Never>?
    private let lock = NSLock()
    /// Cancels every server-trust challenge: a pinning delegate whose pin no server matches.
    private var rejectTrust = false

    static func session(configuration: URLSessionConfiguration, rejectTrust: Bool = false) -> Collector {
        let collector = Collector()
        collector.rejectTrust = rejectTrust
        collector.session = URLSession(configuration: configuration, delegate: collector, delegateQueue: nil)
        return collector
    }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if rejectTrust && challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            completionHandler(.cancelAuthenticationChallenge, nil)
        } else {
            completionHandler(.performDefaultHandling, nil)
        }
    }

    fileprivate func run(_ task: URLSessionTask) async -> CaptureOutcome {
        let data = await withCheckedContinuation { continuation in
            lock.lock()
            received = Data()
            self.continuation = continuation
            lock.unlock()
            task.resume()
        }
        return CaptureOutcome(task: task, data: data)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        received.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let data = received
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(returning: data)
    }
}

/// A delegate that implements only the end of a task, with the session's completion-handler path
/// doing the rest: the "partial delegate" shape.
private final class PartialDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private(set) var session: URLSession!
    private(set) var completions = 0

    static func session() -> PartialDelegate {
        let delegate = PartialDelegate()
        delegate.session = URLSession(configuration: .default, delegate: delegate, delegateQueue: .main)
        return delegate
    }

    fileprivate func run(_ request: URLRequest) async -> CaptureOutcome {
        await withCheckedContinuation { continuation in
            session.dataTask(with: request) { data, response, error in
                continuation.resume(returning: CaptureOutcome(response: response, data: data ?? Data(), error: error))
            }.resume()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        completions += 1
    }
}
