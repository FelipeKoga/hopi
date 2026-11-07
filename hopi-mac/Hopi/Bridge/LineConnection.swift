import Foundation
import Network

/// One connection to an app's bridge: the token as the first line, then NDJSON both ways.
///
/// Lines are split on raw bytes, never with a line reader: a `get` reply is a single line that can
/// hold a 16 MB body. Callbacks run on the main queue, so the viewer's state needs no locks.
final class LineConnection {
    enum Event {
        case line(Data)
        case closed(String?)
    }

    private let connection: NWConnection
    private var buffer = Data()
    private var onEvent: (Event) -> Void = { _ in }
    private var finished = false

    init(endpoint: BridgeEndpoint) {
        connection = NWConnection(
            host: "127.0.0.1",
            port: NWEndpoint.Port(rawValue: endpoint.port) ?? 0,
            using: .tcp
        )
        self.token = endpoint.token
    }

    private let token: String

    /// Opens the connection and sends the token. [onEvent] receives every line, then one `closed`.
    func start(_ onEvent: @escaping (Event) -> Void) {
        self.onEvent = onEvent
        connection.stateUpdateHandler = { [weak self] state in
            MainActor.assumeIsolated {
                switch state {
                case .failed(let error): self?.finish("\(error)")
                case .cancelled: self?.finish(nil)
                default: break
                }
            }
        }
        connection.start(queue: .main)
        sendRaw(token + "\n")
        receive()
    }

    /// Sends one command as a JSON line.
    func send(_ command: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: command),
              let line = String(data: data, encoding: .utf8) else { return }
        sendRaw(line + "\n")
    }

    func close() {
        connection.cancel()
    }

    private func sendRaw(_ text: String) {
        connection.send(content: Data(text.utf8), completion: .contentProcessed { _ in })
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, done, error in
            MainActor.assumeIsolated {
                guard let self else { return }
                if let data { self.consume(data) }
                if let error {
                    self.finish("\(error)")
                } else if done {
                    self.finish(nil)
                } else {
                    self.receive()
                }
            }
        }
    }

    private func consume(_ data: Data) {
        buffer.append(data)
        var start = buffer.startIndex
        while let newline = buffer[start...].firstIndex(of: 0x0A) {
            let line = buffer[start..<newline]
            if !line.isEmpty { onEvent(.line(Data(line))) }
            start = buffer.index(after: newline)
        }
        if start != buffer.startIndex { buffer.removeSubrange(buffer.startIndex..<start) }
    }

    private func finish(_ reason: String?) {
        guard !finished else { return }
        finished = true
        connection.cancel()
        onEvent(.closed(reason))
    }
}
