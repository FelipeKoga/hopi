import Foundation
import Observation

/// The viewer attached to one app: a live list over `tail`, details over a second connection.
///
/// `tail` owns its connection (input after it is read only to notice a disconnect), so `get` and
/// `clear` go on a command connection of their own, one request at a time, replies in order.
@Observable
final class Session {
    enum Status: Equatable {
        case connecting
        case live
        case closed(String?)
    }

    let target: Target
    private(set) var status: Status = .connecting
    private(set) var hello: Hello?
    /// Oldest first, as the app sends them: the backlog and live events append.
    private(set) var transactions: [Summary] = []
    /// Bumped when the app's rule set changes (`{"type":"rules"}` on the tail), so lists fetch again.
    private(set) var rulesRevision = 0

    @ObservationIgnored private var index: [Int64: Int] = [:]
    @ObservationIgnored private var stream: LineConnection?
    @ObservationIgnored private var commands: CommandLine?
    /// One per command sent, in order: each takes reply lines until it returns true (done).
    @ObservationIgnored private var waiting: [(Data?) -> Bool] = []
    @ObservationIgnored private var nextBlob: Int64 = 0

    init(target: Target) {
        self.target = target
    }

    func start() {
        let stream = LineConnection(endpoint: target.endpoint)
        self.stream = stream
        stream.start { [weak self] event in
            guard let self else { return }
            switch event {
            case .line(let data): self.streamLine(data)
            case .closed(let reason): self.status = .closed(reason)
            }
        }
        stream.send(["cmd": "hello"])
        stream.send(["cmd": "tail", "since": 0, "rules": true])
    }

    func stop() {
        stream?.close()
        commands?.close()
        failWaiting()
    }

    /// The whole transaction, bodies included; nil when it is gone or the app went away.
    func detail(_ id: Int64) async -> Detail? {
        guard let data = await request(["cmd": "get", "id": id]) else { return nil }
        return await Task.detached(priority: .userInitiated) {
            try? JSONDecoder().decode(Detail.self, from: data)
        }.value
    }

    /// Clears the app's store, then this list through what the app cleared.
    func clear() async {
        guard let data = await request(["cmd": "clear"]),
              let reply = try? JSONDecoder().decode(ClearReply.self, from: data) else { return }
        let through = reply.through ?? Int64.max
        transactions.removeAll { $0.id <= through }
        reindex()
    }

    // MARK: Rules

    func rules() async throws -> [Rule] {
        guard let lines = await requestLines(["cmd": "rules.list"]) else { throw Self.gone }
        return try lines.map(Self.rule)
    }

    /// Adds [rule], the wire object of `rules.add`. Bodies too long for one line go up as blobs first.
    @discardableResult
    func addRule(_ rule: [String: Any]) async throws -> Rule {
        guard let line = await request(["cmd": "rules.add", "rule": rule]) else { throw Self.gone }
        return try Self.rule(line)
    }

    /// `rules.update`: `enabled`, `priority`, `times` (NSNull clears it), `resetHits`.
    @discardableResult
    func updateRule(_ id: Int64, _ fields: [String: Any]) async throws -> Rule {
        var command = fields
        command["cmd"] = "rules.update"
        command["id"] = id
        guard let line = await request(command) else { throw Self.gone }
        return try Self.rule(line)
    }

    func removeRule(_ id: Int64) async throws {
        guard let line = await request(["cmd": "rules.remove", "id": id]) else { throw Self.gone }
        try Self.check(line)
    }

    func clearRules() async throws {
        guard let line = await request(["cmd": "rules.clear"]) else { throw Self.gone }
        try Self.check(line)
    }

    /// The body object for a rule: inline text when it fits a command line, an uploaded blob otherwise.
    func ruleBody(_ bytes: Data) async throws -> [String: Any] {
        if bytes.count <= 1500, let text = String(data: bytes, encoding: .utf8) { return ["text": text] }
        nextBlob += 1
        let blob = nextBlob
        let base64 = bytes.base64EncodedString()
        // 3072 base64 chars carry 2304 bytes: offsets stay whole bytes.
        var offset = 0
        var start = base64.startIndex
        while start < base64.endIndex {
            let end = base64.index(start, offsetBy: 3072, limitedBy: base64.endIndex) ?? base64.endIndex
            let chunk = String(base64[start..<end])
            guard let line = await request(["cmd": "blob.put", "blob": blob, "offset": offset, "data": chunk]) else { throw Self.gone }
            try Self.check(line)
            offset += Data(base64Encoded: chunk)?.count ?? 0
            start = end
        }
        return ["blob": blob, "size": bytes.count]
    }

    private nonisolated static let gone = WireError(code: "GONE", message: "The app closed the connection.")

    private nonisolated static func check(_ line: Data) throws {
        if (try? JSONDecoder().decode(WireType.self, from: line))?.type == "error" {
            throw (try? JSONDecoder().decode(WireError.self, from: line)) ?? gone
        }
    }

    private nonisolated static func rule(_ line: Data) throws -> Rule {
        try check(line)
        return try JSONDecoder().decode(Rule.self, from: line)
    }

    private func streamLine(_ data: Data) {
        guard let type = try? JSONDecoder().decode(WireType.self, from: data).type else { return }
        switch type {
        case "transaction":
            guard let summary = try? JSONDecoder().decode(Summary.self, from: data) else { return }
            upsert(summary)
        case "hello":
            guard let hello = try? JSONDecoder().decode(Hello.self, from: data), target.endpoint.accepts(hello) else {
                stream?.close()
                status = .closed("This is not \(target.app)'s bridge: its hello lacks the app's proof.")
                return
            }
            self.hello = hello
            status = .live
        case "rules":
            rulesRevision += 1
        default:
            break // notice, error: the list goes on; SLOW_CLIENT ends the connection, shown as closed.
        }
    }

    /// The same id arrives as pending, then complete or failed.
    private func upsert(_ summary: Summary) {
        if let position = index[summary.id] {
            transactions[position] = summary
        } else if let last = transactions.last, summary.id < last.id {
            transactions.append(summary)
            transactions.sort { $0.id < $1.id }
            reindex()
        } else {
            index[summary.id] = transactions.count
            transactions.append(summary)
        }
    }

    private func reindex() {
        index = Dictionary(uniqueKeysWithValues: transactions.enumerated().map { ($1.id, $0) })
    }

    /// A command whose reply is one line.
    private func request(_ command: [String: Any]) async -> Data? {
        let connection = commandConnection()
        return await withCheckedContinuation { continuation in
            waiting.append { line in
                continuation.resume(returning: line)
                return true
            }
            connection.send(command)
        }
    }

    /// A command whose reply is lines up to `{"type":"end"}`, or one `{"type":"error"}` line.
    /// Nil when the connection closed first.
    private func requestLines(_ command: [String: Any]) async -> [Data]? {
        let connection = commandConnection()
        return await withCheckedContinuation { continuation in
            var lines: [Data] = []
            waiting.append { line in
                guard let line else {
                    continuation.resume(returning: nil)
                    return true
                }
                let type = (try? JSONDecoder().decode(WireType.self, from: line))?.type
                if type == "end" {
                    continuation.resume(returning: lines)
                    return true
                }
                lines.append(line)
                if type == "error" {
                    continuation.resume(returning: lines)
                    return true
                }
                return false
            }
            connection.send(command)
        }
    }

    /// The command connection: commands wait until its own hello proves it is the app's bridge,
    /// so a squatter on the socket name never receives a write.
    private func commandConnection() -> CommandLine {
        if let commands { return commands }
        let connection = LineConnection(endpoint: target.endpoint)
        let line = CommandLine(connection: connection)
        connection.start { [weak self] event in
            guard let self else { return }
            switch event {
            case .line(let data):
                if !line.verified {
                    guard let hello = try? JSONDecoder().decode(Hello.self, from: data),
                          self.target.endpoint.accepts(hello) else {
                        connection.close()
                        return
                    }
                    line.verify()
                    return
                }
                if let first = self.waiting.first, first(data) { self.waiting.removeFirst() }
            case .closed:
                self.commands = nil
                self.failWaiting()
            }
        }
        connection.send(["cmd": "hello"])
        commands = line
        return line
    }

    private func failWaiting() {
        let pending = waiting
        waiting = []
        pending.forEach { _ = $0(nil) }
    }
}

/// A connection whose commands are held until its hello is verified, then sent in order.
final class CommandLine {
    let connection: LineConnection
    private(set) var verified = false
    private var held: [[String: Any]] = []

    init(connection: LineConnection) {
        self.connection = connection
    }

    func send(_ command: [String: Any]) {
        if verified { connection.send(command) } else { held.append(command) }
    }

    func verify() {
        verified = true
        held.forEach(connection.send)
        held = []
    }

    func close() {
        connection.close()
    }
}
