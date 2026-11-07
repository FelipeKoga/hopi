import Foundation

// The bridge's wire format (docs/ARCHITECTURE.md, "Wire protocol"): NDJSON, one object per line.
// Unknown fields are ignored, so additive fields on the app's side never break the viewer.

/// The first field of every reply, read before deciding how to decode the rest.
nonisolated struct WireType: Decodable, Sendable {
    let type: String?
}

nonisolated struct Hello: Decodable, Sendable {
    let app: String
    let platform: String
    let count: Int?
    let rules: Bool?
    let perf: Bool?
    /// Whether the viewer may start and stop monitors.
    let perfControl: Bool?
    /// Android only: what the app wrote to its private storage.
    let proof: String?
}

nonisolated enum TransactionState: String, Decodable, Sendable {
    case pending, complete, failed
}

/// One line of `list` or `tail`: what the request table shows. No headers, no bodies.
nonisolated struct Summary: Decodable, Sendable, Identifiable, Hashable {
    let id: Int64
    let method: String
    let url: String
    let code: Int?
    let ms: Int64?
    let reqBytes: Int64
    let resBytes: Int64
    let state: TransactionState
    let source: String?
    let error: String?
    let rule: Int64?
    let applied: String?
}

nonisolated struct WireBody: Decodable, Sendable, Hashable {
    let encoding: String
    let text: String?
    let data: String?
    let truncated: Bool
    let capturedBytes: Int64
    let totalBytes: Int64?
    let contentType: String?
    let skippedReason: String?
}

nonisolated struct Exchange: Decodable, Sendable, Hashable {
    /// `[name, value]` pairs: order and duplicates survive.
    let headers: [[String]]
    let body: WireBody?
}

/// The reply to `get`: everything about one transaction.
nonisolated struct Detail: Decodable, Sendable, Hashable {
    let id: Int64
    let startedAt: Int64?
    let method: String
    let url: String
    let state: TransactionState
    let code: Int?
    let ms: Int64?
    let `protocol`: String?
    let error: String?
    let request: Exchange
    let response: Exchange?
}

nonisolated struct WireError: Decodable, Sendable, Error {
    let code: String
    let message: String
}

nonisolated struct ClearReply: Decodable, Sendable {
    let through: Int64?
}

// MARK: - Rules

/// One rule as `rules.*` replies carry it. Bodies arrive as their size only, never their bytes.
nonisolated struct Rule: Decodable, Sendable, Identifiable, Hashable {
    struct Match: Decodable, Sendable, Hashable {
        let shorthand: String
    }

    struct Delay: Decodable, Sendable, Hashable {
        let beforeRequestMs: Int64
        let beforeResponseMs: Int64
    }

    struct Size: Decodable, Sendable, Hashable {
        let bytes: Int64
    }

    struct Mock: Decodable, Sendable, Hashable {
        let status: Int
        let body: Size?
    }

    /// One side of an edit: decoded only to know it is there and what it changes.
    struct Side: Decodable, Sendable, Hashable {
        let status: Int?
        let method: String?
        let host: String?
        let path: String?
        let setHeaders: [[String]]?
        let removeHeaders: [String]?
        let body: Size?
    }

    struct Edit: Decodable, Sendable, Hashable {
        let request: Side?
        let response: Side?
    }

    struct Unsupported: Decodable, Sendable, Hashable {
        let source: String
        let reason: String
        let applies: Bool
    }

    let id: Int64
    let label: String?
    let enabled: Bool
    let priority: Int
    let times: Int?
    let hits: Int64
    let exhausted: Bool
    let match: Match
    let delay: Delay?
    let mock: Mock?
    let fail: String?
    let edit: Edit?
    let unsupported: [Unsupported]

    /// `mock 500 (61 B)`, `fail timeout`, `edit response`, `delay 2000 ms before response`.
    var action: String {
        var parts: [String] = []
        if let mock { parts.append("mock \(mock.status)" + (mock.body.map { " (\(Format.bytes($0.bytes)))" } ?? "")) }
        if let fail { parts.append("fail \(fail)") }
        if let edit {
            let sides = [edit.request.map { _ in "request" }, edit.response.map { _ in "response" }].compactMap { $0 }
            parts.append("edit " + sides.joined(separator: "+"))
        }
        if let delay, delay.beforeRequestMs > 0 || delay.beforeResponseMs > 0 {
            let when = [
                delay.beforeRequestMs > 0 ? "\(delay.beforeRequestMs) ms before request" : nil,
                delay.beforeResponseMs > 0 ? "\(delay.beforeResponseMs) ms before response" : nil,
            ].compactMap { $0 }
            parts.append("delay " + when.joined(separator: ", "))
        }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Performance

/// `perf.status`: what the app can monitor and what runs.
nonisolated struct PerfStatus: Decodable, Sendable, Hashable {
    struct Running: Decodable, Sendable, Hashable {
        let since: Int64?
        let expiresAt: Int64?
    }

    let platform: String
    let available: [String]
    let remoteControl: Bool
    let running: [String: Running]
    let notices: [String]?
}

/// `perf.sample`: CPU in percent of one core, memory in bytes.
nonisolated struct PerfSample: Decodable, Sendable, Hashable {
    struct Cpu: Decodable, Sendable, Hashable {
        let process: Double
        let hopi: Double?
        let cores: Int?
    }

    struct Memory: Decodable, Sendable, Hashable {
        let javaHeap: Int64?
        let javaMax: Int64?
        let nativeHeap: Int64?
        let resident: Int64?
        let available: Int64?
    }

    let at: Int64
    let cpu: Cpu?
    let memory: Memory?
}

/// `perf.frames`: one closed second of frames.
nonisolated struct PerfFrames: Decodable, Sendable, Hashable {
    let sec: Int64
    let frames: Int
    let slow: Int
    let janky: Int
    let frozen: Int
    let screen: String?
}

/// `perf.event`: a hang, a janky frame, a stall, a memory signal or an earlier ANR.
nonisolated struct PerfEvent: Decodable, Sendable, Hashable, Identifiable {
    let kind: String
    let seq: Int64
    let at: Int64
    let state: String?
    let ms: Double?
    let screen: String?
    let stacks: [[String]]?
    let event: String?
    let level: String?
    let detail: String?
    let description: String?
    let trace: String?

    var id: Int64 { seq }
}

extension WireError: LocalizedError {
    nonisolated var errorDescription: String? { message }
}
