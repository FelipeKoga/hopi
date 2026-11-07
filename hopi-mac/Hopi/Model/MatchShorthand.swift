import Foundation

/// The match shorthand, as the CLI and the Compose viewer read it: `[METHODS ]URL-PATTERN`, METHODS
/// being `GET`, `GET|POST` or `*`, URL-PATTERN `[scheme://]host[:port]path[?query]`. A pattern that
/// starts with `/` matches any host; without a path, any path. `?page=2&debug&!trace` gives the
/// query predicates `page=2`, `debug` (present) and `!trace` (absent). The app never parses it:
/// `rules.add` carries the structured `match` this returns.
nonisolated enum MatchShorthand {
    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func parse(_ text: String) throws -> [String: Any] {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { throw Failure(message: "match: empty") }
        let parts = trimmed.split(whereSeparator: { $0 == " " || $0 == "\t" }).map(String.init)
        guard parts.count <= 2 else { throw Failure(message: "match: '\(text)' has more than a method list and a URL pattern") }
        var match: [String: Any] = [:]
        if parts.count == 2, parts[0] != "*" {
            let methods = parts[0].split(separator: "|").map(String.init)
            for method in methods where method.isEmpty || method == "*" || !method.allSatisfy(isTokenChar) {
                throw Failure(message: "match: '\(method)' is not a method")
            }
            match["methods"] = methods
        }
        var rest = parts.last!
        if let range = rest.range(of: "://") {
            let scheme = rest[..<range.lowerBound].lowercased()
            guard ["http", "https", "*"].contains(scheme) else { throw Failure(message: "match: scheme must be http, https or *, not '\(scheme)'") }
            if scheme != "*" { match["scheme"] = scheme }
            rest = String(rest[range.upperBound...])
        }
        var query: String?
        if let question = rest.firstIndex(of: "?") {
            query = String(rest[rest.index(after: question)...])
            rest = String(rest[..<question])
        }
        guard !rest.contains("#") else { throw Failure(message: "match: no fragment") }
        var path: String?
        if rest.hasPrefix("/") {
            guard match["scheme"] == nil else { throw Failure(message: "match: a pattern with a scheme needs a host (use * for any)") }
            path = rest
        } else {
            let slash = rest.firstIndex(of: "/")
            let authority = slash.map { String(rest[..<$0]) } ?? rest
            path = slash.map { String(rest[$0...]) }
            guard !authority.isEmpty else { throw Failure(message: "match: no host") }
            var host = authority
            if authority.hasPrefix("[") {
                guard let close = authority.firstIndex(of: "]") else { throw Failure(message: "match: unclosed [ in host") }
                host = String(authority[authority.index(after: authority.startIndex)..<close])
                let after = authority[authority.index(after: close)...]
                if !after.isEmpty {
                    guard after.hasPrefix(":") else { throw Failure(message: "match: unexpected '\(after)' after the host") }
                    match["port"] = try port(String(after.dropFirst()))
                }
            } else if let colon = authority.lastIndex(of: ":") {
                host = String(authority[..<colon])
                match["port"] = try port(String(authority[authority.index(after: colon)...]))
            }
            guard !host.isEmpty else { throw Failure(message: "match: no host") }
            if host != "*" { match["host"] = host }
        }
        if let path, path != "/**" { match["path"] = path }
        let predicates = (query ?? "").split(separator: "&").map { item -> [String: Any] in
            if item.hasPrefix("!") { return ["name": String(item.dropFirst()), "absent": true] }
            if let eq = item.firstIndex(of: "=") {
                return ["name": String(item[..<eq]), "value": String(item[item.index(after: eq)...])]
            }
            return ["name": String(item)]
        }
        if !predicates.isEmpty { match["query"] = predicates }
        return match
    }

    /// `GET https://api.example.com:8443/users/42`: one request exactly, without its query.
    static func exact(method: String, url: String) -> String {
        guard let components = URLComponents(string: url), let host = components.host else {
            return "\(method) \(url.split(separator: "?").first.map(String.init) ?? url)"
        }
        let scheme = components.scheme.map { "\($0)://" } ?? ""
        let port = components.port.map { ":\($0)" } ?? ""
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        let bracketed = host.contains(":") ? "[\(host)]" : host
        return "\(method) \(scheme)\(bracketed.lowercased())\(port)\(path)"
    }

    private static func port(_ text: String) throws -> Int {
        guard let port = Int(text), (1...65535).contains(port) else { throw Failure(message: "match: port must be 1..65535, not '\(text)'") }
        return port
    }

    /// RFC 9110 token characters.
    private static func isTokenChar(_ c: Character) -> Bool {
        c.isASCII && (c.isLetter || c.isNumber || "!#$%&'*+-.^_`|~".contains(c))
    }
}
