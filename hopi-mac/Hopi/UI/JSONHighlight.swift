import SwiftUI

/// Colors re-indented JSON: keys, strings, numbers and literals, punctuation dimmed. The text is
/// never changed, only styled; a body too large to style quickly is shown plain.
nonisolated enum JSONHighlight {
    static let limit = 300_000

    static func attributed(_ text: String) -> AttributedString {
        guard text.utf8.count <= limit else { return AttributedString(text) }
        let scalars = Array(text.unicodeScalars)
        var result = AttributedString()
        var i = 0

        func append(_ range: Range<Int>, _ color: Color?) {
            var view = String.UnicodeScalarView()
            view.append(contentsOf: scalars[range])
            var run = AttributedString(String(view))
            if let color { run.foregroundColor = color }
            result.append(run)
        }

        while i < scalars.count {
            let c = scalars[i]
            switch c {
            case "\"":
                var j = i + 1
                var escaped = false
                while j < scalars.count {
                    if escaped { escaped = false } else if scalars[j] == "\\" { escaped = true } else if scalars[j] == "\"" { break }
                    j += 1
                }
                let end = min(j + 1, scalars.count)
                // A string followed by `:` is a key.
                var k = end
                while k < scalars.count, scalars[k] == " " { k += 1 }
                let isKey = k < scalars.count && scalars[k] == ":"
                append(i..<end, isKey ? .purple : .teal)
                i = end
            case "-", "0"..."9":
                var j = i + 1
                while j < scalars.count, "0123456789.eE+-".unicodeScalars.contains(scalars[j]) { j += 1 }
                append(i..<j, .orange)
                i = j
            case "t", "f", "n":
                var j = i
                while j < scalars.count, CharacterSet.letters.contains(scalars[j]) { j += 1 }
                append(i..<j, .pink)
                i = j
            case "{", "}", "[", "]", ",", ":":
                append(i..<i + 1, .secondary)
                i += 1
            default:
                var j = i + 1
                while j < scalars.count, !"\"{}[],:-0123456789tfn".unicodeScalars.contains(scalars[j]) { j += 1 }
                append(i..<j, nil)
                i = j
            }
        }
        return result
    }
}
