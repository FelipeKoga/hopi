import Foundation

/// A session written as HAR 1.2 or NDJSON (one `get` line per request, as `hopi get --json`
/// prints it). Each request is fetched in full with `get`, one at a time.
enum Export {
    enum Format: String, CaseIterable, Identifiable {
        case har = "HAR", ndjson = "NDJSON"
        var id: String { rawValue }
        var fileExtension: String { self == .har ? "har" : "ndjson" }
    }

    struct Result {
        let written: Int
        let gone: Int
    }

    /// Writes [ids] to [url]; [progress] gets the count done so far.
    static func write(_ format: Format, ids: [Int64], session: Session, to url: URL,
                      progress: (Int) -> Void) async throws -> Result {
        var gone = 0
        var entries: [[String: Any]] = []
        var lines = Data()
        for (index, id) in ids.enumerated() {
            progress(index)
            guard let raw = await session.rawDetail(id), isDetail(raw) else {
                gone += 1
                continue
            }
            switch format {
            case .ndjson:
                lines.append(raw)
                lines.append(0x0A)
            case .har:
                guard let detail = try? JSONDecoder().decode(Detail.self, from: raw) else { gone += 1; continue }
                entries.append(entry(detail))
            }
        }
        progress(ids.count)
        let data: Data
        switch format {
        case .ndjson:
            data = lines
        case .har:
            let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"
            let har: [String: Any] = ["log": [
                "version": "1.2",
                "creator": ["name": "Hopi", "version": version],
                "pages": [],
                "entries": entries,
            ]]
            data = try JSONSerialization.data(withJSONObject: har, options: [.prettyPrinted, .withoutEscapingSlashes])
        }
        try data.write(to: url, options: .atomic)
        return Result(written: ids.count - gone, gone: gone)
    }

    private static func isDetail(_ raw: Data) -> Bool {
        (try? JSONDecoder().decode(WireType.self, from: raw))?.type == "transaction"
    }

    nonisolated static func entry(_ detail: Detail) -> [String: Any] {
        let started = detail.startedAt.map { Date(timeIntervalSince1970: Double($0) / 1000) } ?? Date()
        let ms = Double(detail.ms ?? 0)
        let httpVersion = detail.protocol ?? "HTTP/1.1"
        var request: [String: Any] = [
            "method": detail.method,
            "url": detail.url,
            "httpVersion": httpVersion,
            "cookies": [],
            "headers": headers(detail.request.headers),
            "queryString": query(detail.url),
            "headersSize": -1,
            "bodySize": detail.request.body.map { Int($0.totalBytes ?? $0.capturedBytes) } ?? 0,
        ]
        if let body = detail.request.body, let text = body.text ?? body.data {
            request["postData"] = ["mimeType": body.contentType ?? "", "text": text]
        }
        var response: [String: Any] = [
            "status": detail.code ?? 0,
            "statusText": detail.code.map { HTTPURLResponse.localizedString(forStatusCode: $0).capitalized } ?? "",
            "httpVersion": httpVersion,
            "cookies": [],
            "headers": headers(detail.response?.headers ?? []),
            "redirectURL": detail.response?.headers.first { $0.first?.lowercased() == "location" }?.last ?? "",
            "headersSize": -1,
            "bodySize": detail.response?.body.map { Int($0.totalBytes ?? $0.capturedBytes) } ?? -1,
        ]
        var content: [String: Any] = [
            "size": detail.response?.body.map { Int($0.totalBytes ?? $0.capturedBytes) } ?? 0,
            "mimeType": detail.response?.body?.contentType ?? "",
        ]
        if let body = detail.response?.body {
            if let text = body.text {
                content["text"] = text
            } else if let data = body.data {
                content["text"] = data
                content["encoding"] = "base64"
            }
            var notes: [String] = []
            if body.truncated { notes.append("cut at \(body.capturedBytes) bytes by Hopi") }
            if let reason = body.skippedReason { notes.append("not captured: \(reason)") }
            if !notes.isEmpty { content["comment"] = notes.joined(separator: "; ") }
        }
        response["content"] = content
        var entry: [String: Any] = [
            "startedDateTime": ISO8601DateFormatter.har.string(from: started),
            "time": ms,
            "request": request,
            "response": response,
            "cache": [:],
            "timings": ["send": 0, "wait": ms, "receive": 0],
        ]
        if let error = detail.error { entry["comment"] = error }
        return entry
    }

    nonisolated private static func headers(_ pairs: [[String]]) -> [[String: String]] {
        pairs.compactMap { pair in
            guard pair.count == 2 else { return nil }
            return ["name": pair[0], "value": pair[1]]
        }
    }

    nonisolated private static func query(_ url: String) -> [[String: String]] {
        (URLComponents(string: url)?.queryItems ?? []).map { ["name": $0.name, "value": $0.value ?? ""] }
    }
}

extension ISO8601DateFormatter {
    nonisolated(unsafe) static let har: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()
}
