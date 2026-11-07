import Foundation

nonisolated enum Format {
    static func host(_ url: String) -> String {
        URLComponents(string: url)?.host ?? ""
    }

    /// Path and query, as typed in the app.
    static func path(_ url: String) -> String {
        guard let components = URLComponents(string: url) else { return url }
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        return components.percentEncodedQuery.map { "\(path)?\($0)" } ?? path
    }

    static func duration(_ ms: Int64) -> String {
        ms < 1000 ? "\(ms) ms" : String(format: "%.2f s", Double(ms) / 1000)
    }

    static func bytes(_ count: Int64) -> String {
        switch count {
        case ..<1: "—"
        case ..<1000: "\(count) B"
        case ..<1_000_000: String(format: "%.1f KB", Double(count) / 1000)
        default: String(format: "%.1f MB", Double(count) / 1_000_000)
        }
    }

    /// A JSON body re-indented for reading, every value and key order exactly as sent; anything
    /// that is not well-formed JSON comes back as it came.
    static func prettyBody(_ text: String, contentType: String?) -> String {
        let trimmed = text.drop { $0.isWhitespace }
        let looksJSON = contentType?.contains("json") == true || trimmed.first == "{" || trimmed.first == "["
        // Off the main thread (RenderedBody); a full body can be tens of megabytes.
        guard looksJSON, text.utf8.count <= RenderedBody.prettyLimit else { return text }
        return reindent(text) ?? text
    }

    /// Walks the text once, rewriting only the whitespace between tokens.
    static func reindent(_ text: String) -> String? {
        var out = ""
        out.reserveCapacity(text.utf8.count * 2)
        var depth = 0
        var inString = false
        var escaped = false
        var chars = Array(text.unicodeScalars)[...]
        func newline() { out.append("\n"); out.append(String(repeating: "  ", count: depth)) }
        while let c = chars.popFirst() {
            if inString {
                out.unicodeScalars.append(c)
                if escaped { escaped = false } else if c == "\\" { escaped = true } else if c == "\"" { inString = false }
                continue
            }
            switch c {
            case "\"":
                inString = true
                out.unicodeScalars.append(c)
            case "{", "[":
                // An empty object or array stays on one line.
                let close: Unicode.Scalar = c == "{" ? "}" : "]"
                if let next = chars.first(where: { !$0.properties.isWhitespace }), next == close {
                    while let w = chars.first, w != close { chars.removeFirst() }
                    chars.removeFirst()
                    out.unicodeScalars.append(c); out.unicodeScalars.append(close)
                } else {
                    out.unicodeScalars.append(c)
                    depth += 1
                    newline()
                }
            case "}", "]":
                depth -= 1
                if depth < 0 { return nil }
                newline()
                out.unicodeScalars.append(c)
            case ",":
                out.append(",")
                newline()
            case ":":
                out.append(": ")
            case " ", "\t", "\n", "\r":
                continue
            default:
                out.unicodeScalars.append(c)
            }
        }
        return depth == 0 && !inString ? out : nil
    }
}
