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

    @ObservationIgnored private var index: [Int64: Int] = [:]
    @ObservationIgnored private var stream: LineConnection?
    @ObservationIgnored private var commands: LineConnection?
    @ObservationIgnored private var waiting: [(Data?) -> Void] = []

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
        stream.send(["cmd": "tail", "since": 0])
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

    private func streamLine(_ data: Data) {
        guard let type = try? JSONDecoder().decode(WireType.self, from: data).type else { return }
        switch type {
        case "transaction":
            guard let summary = try? JSONDecoder().decode(Summary.self, from: data) else { return }
            upsert(summary)
        case "hello":
            hello = try? JSONDecoder().decode(Hello.self, from: data)
            status = .live
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

    private func request(_ command: [String: Any]) async -> Data? {
        let connection = commandConnection()
        return await withCheckedContinuation { continuation in
            waiting.append { continuation.resume(returning: $0) }
            connection.send(command)
        }
    }

    private func commandConnection() -> LineConnection {
        if let commands { return commands }
        let connection = LineConnection(endpoint: target.endpoint)
        var greeted = false
        connection.start { [weak self] event in
            guard let self else { return }
            switch event {
            case .line(let data):
                // The first line answers this connection's own hello.
                if !greeted { greeted = true; return }
                if !self.waiting.isEmpty { self.waiting.removeFirst()(data) }
            case .closed:
                self.commands = nil
                self.failWaiting()
            }
        }
        connection.send(["cmd": "hello"])
        commands = connection
        return connection
    }

    private func failWaiting() {
        let pending = waiting
        waiting = []
        pending.forEach { $0(nil) }
    }
}
