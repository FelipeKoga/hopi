import SwiftUI

/// The app's rules: what it mocks, fails, delays or edits, with their hits. Fetched again whenever
/// the app says its rule set changed, so rules added from the CLI or the app show up too.
struct RulesView: View {
    @Bindable var viewer: Viewer
    let session: Session

    @State private var rules: [Rule] = []
    @State private var loaded = false
    @State private var selection: Rule.ID?
    @State private var form: RuleDraft?
    @State private var problem: String?

    var body: some View {
        Group {
            if session.hello == nil {
                ProgressView("Connecting…").frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if session.hello?.rules != true {
                ContentUnavailableView("No Rules Here", systemImage: "slider.horizontal.3",
                                       description: Text("This build of \(session.target.app) has no rule engine."))
            } else {
                table
            }
        }
        // Only once the hello said the app has rules: before it, rules.list could only be refused.
        .task(id: [session.rulesRevision, session.hello?.rules == true ? 1 : 0]) {
            if session.hello?.rules == true { await load() }
        }
        .onAppear(perform: takeDraftMock)
        .onChange(of: viewer.draftMock) { takeDraftMock() }
        .sheet(item: $form) { draft in
            RuleFormView(draft: draft, session: session) { form = nil }
        }
        .alert("The App Refused", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(problem ?? "")
        }
        .toolbar {
            ToolbarItem {
                Text(rules.count == 1 ? "1 rule" : "\(rules.count) rules")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .fixedSize()
            }
            .sharedBackgroundVisibility(.hidden)
            ToolbarItem {
                Menu {
                    ForEach(RuleDraft.Kind.allCases) { kind in
                        Button(kind.title) { form = RuleDraft(kind: kind) }
                    }
                } label: {
                    Label("Add Rule", systemImage: "plus")
                }
                .accessibilityIdentifier("add-rule")
                .help("Mock, fail, delay or edit matching requests")
            }
            ToolbarItem {
                Button {
                    run { try await session.clearRules() }
                } label: {
                    Label("Remove All", systemImage: "trash")
                }
                .help("Remove every rule")
                .disabled(rules.isEmpty)
            }
        }
    }

    private var table: some View {
        Table(rules, selection: $selection) {
            TableColumn("On") { rule in
                Toggle("Enabled", isOn: Binding(
                    get: { rule.enabled },
                    set: { on in run { try await session.updateRule(rule.id, ["enabled": on]) } }
                ))
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.mini)
            }
            .width(40)
            TableColumn("Match") { rule in
                VStack(alignment: .leading, spacing: 1) {
                    Text(rule.match.shorthand)
                        .font(.system(.body, design: .monospaced))
                        .lineLimit(1).truncationMode(.middle)
                    if let label = rule.label {
                        Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
                .opacity(rule.enabled && !rule.exhausted ? 1 : 0.5)
            }
            .width(min: 200, ideal: 380)
            TableColumn("Action") { rule in
                HStack(spacing: 6) {
                    RuleTag(text: rule.action)
                    if let warning = warning(rule) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .help(warning)
                    }
                }
            }
            .width(min: 120, ideal: 220)
            TableColumn("Hits") { rule in
                Text(rule.times.map { "\(rule.hits) of \($0)" } ?? "\(rule.hits)")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(rule.exhausted ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 50, ideal: 70, max: 100)
            TableColumn("Priority") { rule in
                Text("\(rule.priority)")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            .width(min: 50, ideal: 60, max: 80)
        }
        .tableStyle(.inset)
        .alternatingRowBackgrounds(.disabled)
        .contextMenu(forSelectionType: Rule.ID.self) { ids in
            if let id = ids.first, let rule = rules.first(where: { $0.id == id }) {
                Button(rule.enabled ? "Disable" : "Enable") {
                    run { try await session.updateRule(id, ["enabled": !rule.enabled]) }
                }
                Button("Reset Hits") {
                    run { try await session.updateRule(id, ["resetHits": true]) }
                }
                if rule.times != nil {
                    Button("Remove Limit") {
                        run { try await session.updateRule(id, ["times": NSNull()]) }
                    }
                }
                Divider()
                Button("Remove", role: .destructive) {
                    run { try await session.removeRule(id) }
                }
            }
        }
        .onDeleteCommand {
            guard let id = selection else { return }
            run { try await session.removeRule(id) }
        }
        .overlay {
            if loaded && rules.isEmpty {
                ContentUnavailableView {
                    Label("No Rules", systemImage: "slider.horizontal.3")
                } description: {
                    Text("Rules mock, fail, delay or edit the app's requests. Right-click a request to mock its response.")
                } actions: {
                    Button("Add Rule") { form = RuleDraft(kind: .mock) }
                        .buttonStyle(.glassProminent)
                }
            }
        }
    }

    private func warning(_ rule: Rule) -> String? {
        guard !rule.unsupported.isEmpty else { return nil }
        return rule.unsupported.map { "\($0.source): \($0.reason)" }.joined(separator: "\n")
    }

    private func takeDraftMock() {
        guard let summary = viewer.draftMock else { return }
        viewer.draftMock = nil
        form = RuleDraft.mock(of: summary)
    }

    private func load() async {
        do {
            rules = try await session.rules()
        } catch {
            problem = error.localizedDescription
        }
        loaded = true
    }

    /// Runs a change; the tail's `rules` notice fetches the list again, or [load] does on failure.
    private func run(_ change: @escaping () async throws -> Void) {
        Task {
            do {
                try await change()
            } catch {
                problem = error.localizedDescription
                await load()
            }
        }
    }
}
