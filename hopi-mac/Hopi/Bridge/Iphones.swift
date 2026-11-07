import Foundation

/// iPhones on USB: each app built by a developer that holds `Library/Caches/hopi/bridge.json` in
/// its data container (read with devicectl, as on a simulator), reached through usbmuxd.
nonisolated enum Iphones {
    static func targets() async -> [Target] {
        let candidates = await Task.detached(priority: .userInitiated) { () -> [(Usbmux.Device, String, String, BridgeEndpoint)] in
            let attached = Usbmux.devices()
            guard !attached.isEmpty else { return [] }
            let names = deviceNames()
            var found: [(Usbmux.Device, String, String, BridgeEndpoint)] = []
            let lock = NSLock()
            for device in attached {
                let udid = names.keys.first { Usbmux.sameUdid($0, device.serial) } ?? device.serial
                let apps = developerApps(udid)
                DispatchQueue.concurrentPerform(iterations: apps.count) { index in
                    guard let data = bridgeFile(udid: udid, bundle: apps[index]),
                          let endpoint = BridgeEndpoint(data: data, usbDevice: device.id) else { return }
                    lock.lock()
                    found.append((device, udid, names[udid] ?? "iPhone", endpoint))
                    lock.unlock()
                }
            }
            return found
        }.value
        var targets: [Target] = []
        for (_, udid, name, endpoint) in candidates {
            guard let hello = await Discovery.probe(endpoint) else { continue }
            targets.append(Target(kind: .iphone, app: hello.app, device: name,
                                  id: "usb:\(udid):\(hello.app)", endpoint: endpoint))
        }
        return targets.sorted { $0.app < $1.app }
    }

    /// Physical devices devicectl knows, by UDID: their names.
    private static func deviceNames() -> [String: String] {
        guard let json = devicectl(["list", "devices"]),
              let devices = (json["result"] as? [String: Any])?["devices"] as? [[String: Any]] else { return [:] }
        var names: [String: String] = [:]
        for device in devices {
            guard let hardware = device["hardwareProperties"] as? [String: Any],
                  hardware["reality"] as? String == "physical",
                  let udid = hardware["udid"] as? String else { continue }
            names[udid] = (device["deviceProperties"] as? [String: Any])?["name"] as? String
        }
        return names
    }

    /// Bundle ids of the apps a developer built and installed: the only ones that can carry Hopi.
    private static func developerApps(_ udid: String) -> [String] {
        guard let json = devicectl(["device", "info", "apps", "--device", udid]),
              let apps = (json["result"] as? [String: Any])?["apps"] as? [[String: Any]] else { return [] }
        return apps.compactMap { app in
            guard app["builtByDeveloper"] as? Bool == true, app["appClip"] as? Bool != true else { return nil }
            return app["bundleIdentifier"] as? String
        }.filter { $0.range(of: #"^[A-Za-z0-9][A-Za-z0-9.-]*$"#, options: .regularExpression) != nil }
    }

    /// The app's `bridge.json`, copied off the iPhone; nil when it has none.
    private static func bridgeFile(udid: String, bundle: String) -> Data? {
        withTemporaryDirectory { directory in
            let destination = directory.appendingPathComponent("bridge.json")
            _ = Shell.data("/usr/bin/xcrun", [
                "devicectl", "device", "copy", "from", "--device", udid,
                "--source", "Library/Caches/hopi/bridge.json", "--destination", destination.path,
                "--domain-type", "appDataContainer", "--domain-identifier", bundle,
            ])
            var isDirectory: ObjCBool = false
            FileManager.default.fileExists(atPath: destination.path, isDirectory: &isDirectory)
            // A destination taken as a directory holds the file under its own name.
            let file = isDirectory.boolValue ? destination.appendingPathComponent("bridge.json") : destination
            return try? Data(contentsOf: file)
        }
    }

    /// devicectl's JSON output for [arguments].
    private static func devicectl(_ arguments: [String]) -> [String: Any]? {
        withTemporaryDirectory { directory in
            let output = directory.appendingPathComponent("out.json")
            _ = Shell.data("/usr/bin/xcrun", ["devicectl"] + arguments + ["--json-output", output.path, "--quiet"])
            guard let data = try? Data(contentsOf: output) else { return nil }
            return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        }
    }

    private static func withTemporaryDirectory<T>(_ body: (URL) -> T?) -> T? {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("hopi-\(UUID().uuidString)")
        guard (try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                       attributes: [.posixPermissions: 0o700])) != nil else { return nil }
        defer { try? FileManager.default.removeItem(at: directory) }
        return body(directory)
    }
}
