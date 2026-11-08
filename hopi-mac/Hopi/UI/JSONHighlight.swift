import AppKit

/// A body's text as the inspector shows it: re-indented when it is JSON, colored while small
/// enough, cut at [displayLimit] characters. Built off the main thread: an 8 MB body takes
/// seconds to re-indent and lay out, and the inspector must stay responsive meanwhile.
nonisolated struct RenderedBody: @unchecked Sendable {
    /// Immutable once built; read on the main thread only after [make] returned.
    let text: NSAttributedString
    /// Characters left out past [displayLimit] (the copy action still has them all).
    let omitted: Int

    /// What a text view lays out in one go: past it the rest is left out, with a note.
    static let displayLimit = 2_000_000
    /// Re-indenting walks every character; past this the body is shown as sent.
    static let prettyLimit = 16 << 20

    static func make(_ text: String, contentType: String?) -> RenderedBody {
        let pretty = text.utf8.count <= prettyLimit ? Format.prettyBody(text, contentType: contentType) : text
        if Task.isCancelled { return RenderedBody(text: NSAttributedString(), omitted: 0) }
        let isJSON = pretty != text || contentType?.contains("json") == true
        let units = pretty.utf16
        let shown: String
        let omitted: Int
        if units.count > displayLimit {
            let end = units.index(units.startIndex, offsetBy: displayLimit)
            // String subscripts round a UTF-16 index down to a scalar: a surrogate pair stays whole.
            let cut = String(pretty[..<end])
            shown = cut
            omitted = units.count - cut.utf16.count
        } else {
            shown = pretty
            omitted = 0
        }
        return RenderedBody(text: isJSON ? JSONHighlight.highlight(shown) : JSONHighlight.plain(shown), omitted: omitted)
    }
}

/// Colors re-indented JSON: keys, strings, numbers and literals, punctuation dimmed. The text is
/// never changed, only styled; a body too large to style quickly is shown plain. One attribute
/// run per token over a single string (UTF-16 ranges), not one string per token.
nonisolated enum JSONHighlight {
    static let limit = 1_000_000

    static var font: NSFont {
        NSFont.monospacedSystemFont(ofSize: NSFont.preferredFont(forTextStyle: .callout).pointSize, weight: .regular)
    }

    static func plain(_ text: String) -> NSAttributedString {
        NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.labelColor])
    }

    static func highlight(_ text: String) -> NSAttributedString {
        let result = NSMutableAttributedString(string: text, attributes: [.font: font, .foregroundColor: NSColor.labelColor])
        let u = Array(text.utf16)
        guard u.count <= limit else { return result }
        let quote: UInt16 = 0x22, backslash: UInt16 = 0x5C, colon: UInt16 = 0x3A, space: UInt16 = 0x20
        func isDigit(_ c: UInt16) -> Bool { c >= 0x30 && c <= 0x39 }
        func isNumberPart(_ c: UInt16) -> Bool { isDigit(c) || c == 0x2E || c == 0x65 || c == 0x45 || c == 0x2B || c == 0x2D }
        func isLetter(_ c: UInt16) -> Bool { (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) }
        func isPunctuation(_ c: UInt16) -> Bool { c == 0x7B || c == 0x7D || c == 0x5B || c == 0x5D || c == 0x2C || c == colon }
        func color(_ range: Range<Int>, _ color: NSColor) {
            result.addAttribute(.foregroundColor, value: color, range: NSRange(location: range.lowerBound, length: range.count))
        }

        result.beginEditing()
        var i = 0
        var steps = 0
        while i < u.count {
            steps += 1
            if steps & 0xFFFF == 0, Task.isCancelled { break }
            let c = u[i]
            if c == quote {
                var j = i + 1
                var escaped = false
                while j < u.count {
                    if escaped { escaped = false } else if u[j] == backslash { escaped = true } else if u[j] == quote { break }
                    j += 1
                }
                let end = min(j + 1, u.count)
                // A string followed by `:` is a key.
                var k = end
                while k < u.count, u[k] == space { k += 1 }
                color(i..<end, k < u.count && u[k] == colon ? .systemPurple : .systemTeal)
                i = end
            } else if c == 0x2D || isDigit(c) {
                var j = i + 1
                while j < u.count, isNumberPart(u[j]) { j += 1 }
                color(i..<j, .systemOrange)
                i = j
            } else if c == 0x74 || c == 0x66 || c == 0x6E { // t, f, n
                var j = i
                while j < u.count, isLetter(u[j]) { j += 1 }
                color(i..<max(j, i + 1), .systemPink)
                i = max(j, i + 1)
            } else if isPunctuation(c) {
                color(i..<i + 1, .secondaryLabelColor)
                i += 1
            } else {
                i += 1
            }
        }
        result.endEditing()
        return result
    }
}
