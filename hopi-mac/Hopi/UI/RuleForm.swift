import SwiftUI

/// What the add-rule sheet edits: plain values, turned into the `rules.add` object by [rule].
struct RuleDraft: Identifiable {
    enum Kind: String, CaseIterable, Identifiable {
        case mock, fail, delay, edit
        var id: String { rawValue }

        var title: String {
            switch self {
            case .mock: "Mock"
            case .fail: "Fail"
            case .delay: "Delay"
            case .edit: "Edit"
            }
        }
    }

    static let failures = ["timeout", "reset", "unknown-host", "cancel"]

    let id = UUID()
    var kind: Kind
    var match = ""
    var label = ""
    var times = ""
    var priority = ""
    /// Mock: the record whose status, headers and body the app copies.
    var fromTransaction: Int64?
    var status = ""
    /// `Name: value`, one per line.
    var headers = ""
    var body = ""
    var failure = "timeout"
    var beforeRequestMs = ""
    var beforeResponseMs = ""
    var requestMethod = ""
    var requestHost = ""
    var requestPort = ""
    var requestPath = ""
    var requestHeaders = ""
    var requestBody = ""
    var removeRequestHeaders = ""

    init(kind: Kind) {
        self.kind = kind
    }

    /// "Mock This Response": that request exactly, its response copied in the app.
    static func mock(of summary: Summary) -> RuleDraft {
        var draft = RuleDraft(kind: .mock)
        draft.match = MatchShorthand.exact(method: summary.method, url: summary.url)
        draft.fromTransaction = summary.id
        return draft
    }

    /// The rule object for `rules.add`; bodies go through [upload] (inline text or a blob).
    func rule(upload: (Data) async throws -> [String: Any]) async throws -> [String: Any] {
        var rule: [String: Any] = ["match": try MatchShorthand.parse(match)]
        if let label = trimmed(label) { rule["label"] = label }
        if let times = trimmed(times) { rule["times"] = try int(times, "Times", 1...1_000_000) }
        if let priority = trimmed(priority) { rule["priority"] = try int(priority, "Priority", -1_000_000...1_000_000) }
        switch kind {
        case .mock:
            var mock: [String: Any] = [:]
            if let status = trimmed(status) { mock["status"] = try int(status, "Status", 100...599) }
            if let fromTransaction { mock["fromTransaction"] = fromTransaction }
            guard mock["status"] != nil || fromTransaction != nil else { throw Problem("A mock needs a status.") }
            let pairs = try headerPairs(headers)
            if !pairs.isEmpty { mock["headers"] = pairs }
            if !body.isEmpty { mock["body"] = try await upload(Data(body.utf8)) }
            rule["mock"] = mock
        case .fail:
            rule["fail"] = failure
        case .delay:
            let before = try trimmed(beforeRequestMs).map { try int($0, "Before request", 0...600_000) } ?? 0
            let after = try trimmed(beforeResponseMs).map { try int($0, "Before response", 0...600_000) } ?? 0
            guard before > 0 || after > 0 else { throw Problem("Give a delay before the request or before the response.") }
            rule["delay"] = ["beforeRequestMs": before, "beforeResponseMs": after]
        case .edit:
            var request: [String: Any] = [:]
            if let method = trimmed(requestMethod) { request["method"] = method }
            if let host = trimmed(requestHost) { request["host"] = host }
            if let port = trimmed(requestPort) { request["port"] = try int(port, "Port", 1...65535) }
            if let path = trimmed(requestPath) { request["path"] = path }
            let setRequest = try headerPairs(requestHeaders)
            if !setRequest.isEmpty { request["setHeaders"] = setRequest }
            let removed = removeRequestHeaders.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            if !removed.isEmpty { request["removeHeaders"] = removed }
            if !requestBody.isEmpty { request["body"] = try await upload(Data(requestBody.utf8)) }
            var response: [String: Any] = [:]
            if let status = trimmed(status) { response["status"] = try int(status, "Status", 100...599) }
            let setResponse = try headerPairs(headers)
            if !setResponse.isEmpty { response["setHeaders"] = setResponse }
            if !body.isEmpty { response["body"] = try await upload(Data(body.utf8)) }
            guard !request.isEmpty || !response.isEmpty else { throw Problem("Change the request or the response.") }
            var edit: [String: Any] = [:]
            if !request.isEmpty { edit["request"] = request }
            if !response.isEmpty { edit["response"] = response }
            rule["edit"] = edit
        }
        return rule
    }

    struct Problem: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }

    private func trimmed(_ text: String) -> String? {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private func int(_ text: String, _ field: String, _ range: ClosedRange<Int>) throws -> Int {
        guard let value = Int(text), range.contains(value) else {
            throw Problem("\(field): a number from \(range.lowerBound) to \(range.upperBound), not “\(text)”.")
        }
        return value
    }

    private func headerPairs(_ text: String) throws -> [[String]] {
        try text.split(whereSeparator: \.isNewline).compactMap { line in
            let line = line.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { return nil }
            guard let colon = line.firstIndex(of: ":") else { throw Problem("Headers: “\(line)” has no colon; write Name: value.") }
            return [line[..<colon].trimmingCharacters(in: .whitespaces),
                    line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)]
        }
    }
}

/// The add-rule sheet. The app validates what this cannot check, and its refusal shows as is.
struct RuleFormView: View {
    @State var draft: RuleDraft
    let session: Session
    let dismiss: () -> Void

    @State private var problem: String?
    @State private var saving = false

    var body: some View {
        VStack(spacing: 0) {
            Form {
                Section {
                    Picker("Action", selection: $draft.kind) {
                        ForEach(RuleDraft.Kind.allCases) { Text($0.title).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .disabled(draft.fromTransaction != nil)
                    TextField("Match", text: $draft.match, prompt: Text("GET api.example.com/users/*"))
                        .font(.system(.body, design: .monospaced))
                        .accessibilityIdentifier("rule-match")
                    TextField("Label", text: $draft.label, prompt: Text("Optional"))
                } footer: {
                    Text("Methods (GET, GET|POST or *), then [scheme://]host[:port]path[?query]. * matches one segment, ** any.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                kindSection
                Section("Limits") {
                    TextField("Times", text: $draft.times, prompt: Text("Every request"))
                    TextField("Priority", text: $draft.priority, prompt: Text("0"))
                }
            }
            .formStyle(.grouped)
            Divider()
            HStack {
                if let problem {
                    Label(problem, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                        .lineLimit(3)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("rule-problem")
                }
                Spacer()
                Button("Cancel", role: .cancel, action: dismiss)
                    .keyboardShortcut(.cancelAction)
                Button("Add Rule", action: save)
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.glassProminent)
                    .disabled(saving || draft.match.trimmingCharacters(in: .whitespaces).isEmpty)
                    .accessibilityIdentifier("rule-save")
            }
            .padding(14)
        }
        .frame(width: 520, height: 560)
    }

    @ViewBuilder
    private var kindSection: some View {
        switch draft.kind {
        case .mock:
            Section {
                TextField("Status", text: $draft.status, prompt: Text(draft.fromTransaction == nil ? "200" : "As recorded"))
                    .accessibilityIdentifier("rule-status")
                editor("Headers", $draft.headers, prompt: "Content-Type: application/json")
                editor("Body", $draft.body, prompt: draft.fromTransaction == nil ? "{}" : "As recorded", height: 140, id: "rule-body")
            } header: {
                Text("Response")
            } footer: {
                if let id = draft.fromTransaction {
                    Text("Status, headers and body are copied from request #\(id) in the app; what you give here replaces them.")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        case .fail:
            Section("Failure") {
                Picker("Fail with", selection: $draft.failure) {
                    ForEach(RuleDraft.failures, id: \.self) { Text($0).tag($0) }
                }
            }
        case .delay:
            Section("Delay, in milliseconds") {
                TextField("Before request", text: $draft.beforeRequestMs, prompt: Text("0"))
                TextField("Before response", text: $draft.beforeResponseMs, prompt: Text("0"))
            }
        case .edit:
            Section("Request") {
                TextField("Method", text: $draft.requestMethod, prompt: Text("Unchanged"))
                TextField("Host", text: $draft.requestHost, prompt: Text("Unchanged"))
                TextField("Port", text: $draft.requestPort, prompt: Text("Unchanged"))
                TextField("Path", text: $draft.requestPath, prompt: Text("Unchanged"))
                editor("Set headers", $draft.requestHeaders, prompt: "Authorization: Bearer test")
                TextField("Remove headers", text: $draft.removeRequestHeaders, prompt: Text("Cookie, X-Trace"))
                editor("Body", $draft.requestBody, prompt: "Unchanged", height: 80)
            }
            Section("Response") {
                TextField("Status", text: $draft.status, prompt: Text("Unchanged"))
                editor("Set headers", $draft.headers, prompt: "Cache-Control: no-store")
                editor("Body", $draft.body, prompt: "Unchanged", height: 120)
            }
        }
    }

    private func editor(_ title: String, _ text: Binding<String>, prompt: String, height: CGFloat = 54, id: String = "") -> some View {
        LabeledContent(title) {
            TextField(title, text: text, prompt: Text(prompt), axis: .vertical)
                .labelsHidden()
                .accessibilityIdentifier(id)
                .font(.system(.callout, design: .monospaced))
                .lineLimit(3...(Int(height / 14)))
        }
    }

    private func save() {
        saving = true
        problem = nil
        Task {
            defer { saving = false }
            do {
                let rule = try await draft.rule(upload: session.ruleBody)
                try await session.addRule(rule)
                dismiss()
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}
