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
