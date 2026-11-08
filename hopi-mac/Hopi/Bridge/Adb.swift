import Foundation

/// Android bridges through adb (docs/ARCHITECTURE.md, "The bridge + CLI", Android bullet).
///
/// The app listens on the abstract socket `@hopi.<process name>`. The viewer lists those sockets
/// in `/proc/net/unix`, reads the proof file with `run-as` (debuggable builds only), forwards a host
/// port to the socket, sends the key as the first line, and requires `hello.proof` to match: a
/// squatter on the name cannot read the app's private storage, so it cannot pass.
nonisolated enum Adb {
    static var appPattern: Regex<Substring> { /[A-Za-z0-9_][A-Za-z0-9._:-]{0,254}/ }
    static var serialPattern: Regex<Substring> { /[A-Za-z0-9_][A-Za-z0-9._:-]{0,127}/ }
    private static var proofFile: Regex<(Substring, Substring, Substring)> { /([0-9a-f]{64})\n([0-9a-f]{64})/ }

    static var path: String? {
        var candidates: [String] = []
        if let home = ProcessInfo.processInfo.environment["ANDROID_HOME"], !home.isEmpty {
            candidates.append("\(home)/platform-tools/adb")
        }
        candidates.append(NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb")
        candidates.append("/opt/homebrew/bin/adb")
        candidates.append("/usr/local/bin/adb")
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    /// Every app with a bridge on every device in the `device` state.
    static func targets() async -> [Target] {
        guard let adb = path else { return [] }
        var found: [Target] = []
        for (serial, model) in devices(adb) {
            let forwards = existingForwards(adb, serial: serial)
            for app in bridges(adb, serial: serial) {
                let remote = "localabstract:hopi.\(app)"
                // Only a forward this viewer made is reused: one the CLI made goes when the command ends.
                let mine = Forwards.port(serial: serial, remote: remote).flatMap { forwards[remote] == $0 ? $0 : nil }
                guard let secrets = readProof(adb, serial: serial, app: app),
                      let port = mine ?? forward(adb, serial: serial, app: app)
                else { continue }
                Forwards.remember(serial: serial, remote: remote, port: port)
                let endpoint = BridgeEndpoint(port: port, token: secrets.key, proof: secrets.proof)
                guard let hello = await Discovery.probe(endpoint), hello.app == app else { continue }
                found.append(Target(kind: .android, app: hello.app, device: model ?? serial,
                                    id: "android:\(serial):\(app)", endpoint: endpoint))
            }
        }
        return found
    }

    /// `adb devices -l`: (serial, model) of each device ready for commands.
    private static func devices(_ adb: String) -> [(String, String?)] {
        guard let output = Shell.text(adb, ["devices", "-l"]) else { return [] }
        return output.split(separator: "\n").compactMap { line in
            let fields = line.split(whereSeparator: \.isWhitespace).map(String.init)
            guard fields.count >= 2, fields[1] == "device", fields[0].wholeMatch(of: serialPattern) != nil else { return nil }
            let model = fields.dropFirst(2).first { $0.hasPrefix("model:") }.map { String($0.dropFirst(6)).replacingOccurrences(of: "_", with: " ") }
            return (fields[0], model)
        }
    }

    /// The process names of the `@hopi.<name>` sockets, without duplicates (a client repeats one).
    private static func bridges(_ adb: String, serial: String) -> [String] {
        guard let output = Shell.text(adb, ["-s", serial, "exec-out", "cat", "/proc/net/unix"]) else { return [] }
        var names = Set<String>()
        for line in output.split(separator: "\n") {
            guard let last = line.split(whereSeparator: \.isWhitespace).last, last.hasPrefix("@hopi.") else { continue }
            let name = String(last.dropFirst(6))
            if name.wholeMatch(of: appPattern) != nil { names.insert(name) }
        }
        return names.sorted()
    }

    /// The proof `hello` must show and the key to send first. Never logged.
    private static func readProof(_ adb: String, serial: String, app: String) -> (proof: String, key: String)? {
        let package = String(app.split(separator: ":").first ?? Substring(app))
        guard let output = Shell.text(adb, ["-s", serial, "exec-out", "run-as", package, "cat", "cache/hopi/\(app).proof"]),
              let match = output.trimmingCharacters(in: .whitespacesAndNewlines).wholeMatch(of: proofFile) else { return nil }
        return (String(match.1), String(match.2))
    }

    /// `adb forward --list`, this device's forwards as remote → host port.
    private static func existingForwards(_ adb: String, serial: String) -> [String: UInt16] {
        guard let output = Shell.text(adb, ["-s", serial, "forward", "--list"]) else { return [:] }
        var result: [String: UInt16] = [:]
        for line in output.split(separator: "\n") {
            let fields = line.split(whereSeparator: \.isWhitespace)
            guard fields.count == 3, fields[0] == serial, fields[1].hasPrefix("tcp:"),
                  let port = UInt16(fields[1].dropFirst(4)) else { continue }
            result[String(fields[2])] = port
        }
        return result
    }

    /// `adb forward tcp:0 localabstract:hopi.<app>` prints the host port adb picked.
    private static func forward(_ adb: String, serial: String, app: String) -> UInt16? {
        Shell.text(adb, ["-s", serial, "forward", "tcp:0", "localabstract:hopi.\(app)"])
            .flatMap { UInt16($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
}

/// The forwards this viewer created, by device and remote socket: reused across scans so they do
/// not pile up, while a forward another process made (and may remove) never is.
nonisolated enum Forwards {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var ports: [String: UInt16] = [:]

    static func port(serial: String, remote: String) -> UInt16? {
        lock.withLock { ports["\(serial) \(remote)"] }
    }

    static func remember(serial: String, remote: String, port: UInt16) {
        lock.withLock { ports["\(serial) \(remote)"] = port }
    }
}

/// Runs a tool and returns its standard output when it exits 0.
nonisolated enum Shell {
    static func data(_ executable: String, _ arguments: [String]) -> Data? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = FileHandle.nullDevice
        do { try process.run() } catch { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return process.terminationStatus == 0 ? data : nil
    }

    static func text(_ executable: String, _ arguments: [String]) -> String? {
        data(executable, arguments).flatMap { String(data: $0, encoding: .utf8) }
    }
}
