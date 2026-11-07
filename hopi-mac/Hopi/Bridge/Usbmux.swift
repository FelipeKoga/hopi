import Foundation

/// usbmuxd, the macOS daemon that carries TCP to iPhones on USB: plist messages behind a 16-byte
/// header on `/var/run/usbmuxd`. `ListDevices` names the attached devices; `Connect` turns the
/// socket into a stream to one port on one device.
nonisolated enum Usbmux {
    static let socketPath = "/var/run/usbmuxd"

    struct Device: Sendable {
        let id: Int
        /// The UDID as usbmuxd writes it; compare with [sameUdid].
        let serial: String
    }

    /// The devices attached over USB (not Wi-Fi). Empty when usbmuxd does not answer.
    static func devices() -> [Device] {
        guard let reply = exchange(message(["MessageType": "ListDevices"])),
              let list = reply["DeviceList"] as? [[String: Any]] else { return [] }
        return list.compactMap { entry in
            guard let id = entry["DeviceID"] as? Int,
                  let properties = entry["Properties"] as? [String: Any],
                  properties["ConnectionType"] as? String == "USB",
                  let serial = properties["SerialNumber"] as? String else { return nil }
            return Device(id: id, serial: serial)
        }
    }

    /// The `Connect` request for [port] on device [id]; usbmuxd wants the port in network order.
    static func connect(device id: Int, port: UInt16) -> Data {
        message(["MessageType": "Connect", "DeviceID": id, "PortNumber": Int(port.bigEndian)])
    }

    /// The `Result` of a reply body (the bytes after the header); 0 is success.
    static func result(_ body: Data) -> Int? {
        (try? PropertyListSerialization.propertyList(from: body, format: nil) as? [String: Any])?["Result"] as? Int
    }

    /// The body length a 16-byte header announces.
    static func bodyLength(_ header: Data) -> Int? {
        guard header.count == 16 else { return nil }
        let total = header.prefix(4).enumerated().reduce(0) { $0 | Int($1.element) << (8 * $1.offset) }
        return total >= 16 ? total - 16 : nil
    }

    static func sameUdid(_ a: String, _ b: String) -> Bool {
        func key(_ s: String) -> String { s.replacingOccurrences(of: "-", with: "").uppercased() }
        return key(a) == key(b)
    }

    private static func message(_ fields: [String: Any]) -> Data {
        var plist = fields
        plist["ClientVersionString"] = "hopi"
        plist["ProgName"] = "Hopi"
        plist["kLibUSBMuxVersion"] = 3
        let body = (try? PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)) ?? Data()
        var header = Data()
        for value in [UInt32(16 + body.count), 1, 8, 1] {
            withUnsafeBytes(of: value.littleEndian) { header.append(contentsOf: $0) }
        }
        return header + body
    }

    /// One request and its reply on a fresh blocking socket.
    private static func exchange(_ request: Data) -> [String: Any]? {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }
        var timeout = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_un()
        address.sun_family = sa_family_t(AF_UNIX)
        withUnsafeMutableBytes(of: &address.sun_path) { path in
            for (i, byte) in socketPath.utf8.enumerated() where i < path.count - 1 { path[i] = byte }
        }
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.connect(fd, $0, socklen_t(MemoryLayout<sockaddr_un>.size))
            }
        }
        guard connected == 0, request.withUnsafeBytes({ send(fd, $0.baseAddress, $0.count, 0) }) == request.count,
              let header = read(fd, 16), let length = bodyLength(header), let body = read(fd, length) else { return nil }
        return try? PropertyListSerialization.propertyList(from: body, format: nil) as? [String: Any]
    }

    private static func read(_ fd: Int32, _ count: Int) -> Data? {
        var data = Data(count: count)
        var got = 0
        while got < count {
            let n = data.withUnsafeMutableBytes { recv(fd, $0.baseAddress! + got, count - got, 0) }
            guard n > 0 else { return nil }
            got += n
        }
        return data
    }
}
