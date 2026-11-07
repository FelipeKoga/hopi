import Foundation

/// Where an app's bridge listens and how to open it: the first line to send (the token of
/// `bridge.json`, or Android's key) and, on Android, the proof `hello` must show.
nonisolated struct BridgeEndpoint: Sendable, Hashable {
    let port: UInt16
    let token: String
    var proof: String? = nil
    /// An iPhone on USB: usbmuxd's id for it; [port] is then the bridge's port on the iPhone.
    var usbDevice: Int? = nil

    init(port: UInt16, token: String, proof: String?) {
        self.port = port
        self.token = token
        self.proof = proof
    }

    init?(data: Data, usbDevice: Int? = nil) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let port = json["port"] as? Int, let token = json["token"] as? String,
              (1...65535).contains(port), token.count == 64 else { return nil }
        self.port = UInt16(port)
        self.token = token
        self.usbDevice = usbDevice
    }

    init?(file: URL) {
        guard let data = try? Data(contentsOf: file) else { return nil }
        self.init(data: data)
    }
}

/// An app with a bridge the viewer can reach.
nonisolated struct Target: Sendable, Hashable, Identifiable {
    enum Kind: Sendable, Hashable { case android, iphone, simulator, jvm }

    let kind: Kind
    /// The app's id as the app states it in `hello` (bundle id, applicationId or `hopi.appId`).
    let app: String
    /// The device's model, the simulator's name, or the JVM process id.
    let device: String
    /// Stable across scans while the app runs: the device and app, or the `bridge.json` path.
    let id: String
    let endpoint: BridgeEndpoint
}

/// Finds the bridges this Mac can reach: Android devices, booted simulators, JVM processes.
nonisolated enum Discovery {
    static func scan() async -> [Target] {
        async let android = Adb.targets()
        async let iphones = Iphones.targets()
        let files = await fileTargets()
        return await android + iphones + files
    }

    private static func fileTargets() async -> [Target] {
        let candidates = jvmFiles() + simulatorFiles()
        return await withTaskGroup(of: Target?.self) { group in
            for (kind, device, file) in candidates {
                group.addTask {
                    guard let endpoint = BridgeEndpoint(file: file),
                          let hello = await probe(endpoint) else { return nil }
                    return Target(kind: kind, app: hello.app, device: device, id: file.path, endpoint: endpoint)
                }
            }
            var found: [Target] = []
            for await target in group { if let target { found.append(target) } }
            return found.sorted { $0.app < $1.app }
        }
    }

    /// `<java.io.tmpdir>/hopi/<pid>/bridge.json`. The JVM's tmpdir on macOS is the user's temporary
    /// directory, read here from the system rather than `$TMPDIR`, which a launcher may change.
    private static func jvmFiles() -> [(Target.Kind, String, URL)] {
        let root = URL(fileURLWithPath: userTemporaryDirectory).appendingPathComponent("hopi")
        let pids = (try? FileManager.default.contentsOfDirectory(atPath: root.path)) ?? []
        return pids.map { (.jvm, "JVM \($0)", root.appendingPathComponent($0).appendingPathComponent("bridge.json")) }
    }

    private static var userTemporaryDirectory: String {
        var buffer = [CChar](repeating: 0, count: Int(PATH_MAX))
        guard confstr(_CS_DARWIN_USER_TEMP_DIR, &buffer, buffer.count) > 0 else { return NSTemporaryDirectory() }
        return String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    /// Each booted simulator's apps with `Library/Caches/hopi/bridge.json` in their data container.
    private static func simulatorFiles() -> [(Target.Kind, String, URL)] {
        guard let list = Shell.data("/usr/bin/xcrun", ["simctl", "list", "devices", "booted", "-j"]),
              let json = try? JSONSerialization.jsonObject(with: list) as? [String: Any],
              let runtimes = json["devices"] as? [String: [[String: Any]]] else { return [] }
        var files: [(Target.Kind, String, URL)] = []
        for device in runtimes.values.flatMap({ $0 }) {
            guard let udid = device["udid"] as? String, let name = device["name"] as? String,
                  let apps = Shell.data("/usr/bin/xcrun", ["simctl", "listapps", udid]),
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
                    case .line(let data):
                        let hello = try? JSONDecoder().decode(Hello.self, from: data)
                        resume(hello.flatMap { endpoint.accepts($0) ? $0 : nil })
                    case .closed: resume(nil)
                    }
                }
                connection.send(["cmd": "hello"])
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) { resume(nil) }
            }
        }
    }
}

extension BridgeEndpoint {
    /// Android: the bridge proves itself with the proof only the app could write (constant time).
    nonisolated func accepts(_ hello: Hello) -> Bool {
        guard let proof else { return true }
        guard let shown = hello.proof, shown.utf8.count == proof.utf8.count else { return false }
        return zip(shown.utf8, proof.utf8).reduce(0) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
