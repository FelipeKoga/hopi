import Foundation

/// Where an app's bridge listens: `bridge.json`'s port and token (iOS simulator and JVM apps).
nonisolated struct BridgeEndpoint: Sendable, Hashable {
    let port: UInt16
    let token: String

    init?(file: URL) {
        guard let data = try? Data(contentsOf: file),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let port = json["port"] as? Int, let token = json["token"] as? String,
              (1...65535).contains(port), token.count == 64 else { return nil }
        self.port = UInt16(port)
        self.token = token
    }
}

/// An app with a bridge the viewer can reach.
nonisolated struct Target: Sendable, Hashable, Identifiable {
    enum Kind: Sendable, Hashable { case simulator, jvm }

    let kind: Kind
    /// The app's id as the app states it in `hello` (bundle id, applicationId or `hopi.appId`).
    let app: String
    /// The simulator's name, or the JVM process id.
    let device: String
    let file: URL
    let endpoint: BridgeEndpoint

    var id: URL { file }
}

/// Finds the bridges on this Mac: the booted simulators' apps and the JVM processes.
nonisolated enum Discovery {
    static func scan() async -> [Target] {
        let candidates = jvmFiles() + simulatorFiles()
        return await withTaskGroup(of: Target?.self) { group in
            for (kind, device, file) in candidates {
                group.addTask {
                    guard let endpoint = BridgeEndpoint(file: file),
                          let hello = await probe(endpoint) else { return nil }
                    return Target(kind: kind, app: hello.app, device: device, file: file, endpoint: endpoint)
                }
            }
            var found: [Target] = []
            for await target in group { if let target { found.append(target) } }
            return found.sorted { ($0.kind == .simulator ? 0 : 1, $0.app) < ($1.kind == .simulator ? 0 : 1, $1.app) }
        }
    }

    /// `<java.io.tmpdir>/hopi/<pid>/bridge.json`; the JVM's tmpdir is this user's `$TMPDIR`.
    private static func jvmFiles() -> [(Target.Kind, String, URL)] {
        let root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("hopi")
        let pids = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        return pids.map { (.jvm, "JVM \($0)", root.appendingPathComponent($0).appendingPathComponent("bridge.json")) }
    }

    /// Each booted simulator's apps with `Library/Caches/hopi/bridge.json` in their data container.
    private static func simulatorFiles() -> [(Target.Kind, String, URL)] {
        guard let list = run(["simctl", "list", "devices", "booted", "-j"]),
              let json = try? JSONSerialization.jsonObject(with: list) as? [String: Any],
              let runtimes = json["devices"] as? [String: [[String: Any]]] else { return [] }
        var files: [(Target.Kind, String, URL)] = []
        for device in runtimes.values.flatMap({ $0 }) {
            guard let udid = device["udid"] as? String, let name = device["name"] as? String,
                  let apps = run(["simctl", "listapps", udid]),
                  let plist = try? PropertyListSerialization.propertyList(from: apps, format: nil) as? [String: Any]
            else { continue }
            for app in plist.values.compactMap({ $0 as? [String: Any] }) {
                guard let container = app["DataContainer"] as? String,
                      let url = URL(string: container) else { continue }
                let file = url.appendingPathComponent("Library/Caches/hopi/bridge.json")
                if FileManager.default.fileExists(atPath: file.path) { files.append((.simulator, name, file)) }
            }
        }
        return files
    }

    /// Connects, reads `hello`, and closes: the app's id, and proof the bridge is alive.
    static func probe(_ endpoint: BridgeEndpoint) async -> Hello? {
        await withCheckedContinuation { continuation in
            Task { @MainActor in
                let connection = LineConnection(endpoint: endpoint)
                var resumed = false
                let resume = { (hello: Hello?) in
                    guard !resumed else { return }
                    resumed = true
                    connection.close()
                    continuation.resume(returning: hello)
                }
                connection.start { event in
                    switch event {
                    case .line(let data): resume(try? JSONDecoder().decode(Hello.self, from: data))
                    case .closed: resume(nil)
                    }
                }
                connection.send(["cmd": "hello"])
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { resume(nil) }
            }
        }
    }

    private static func run(_ arguments: [String]) -> Data? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/xcrun")
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return process.terminationStatus == 0 ? data : nil
    }
}
