import AppKit
import SwiftUI

/// One request in the inspector, fetched with `get` when selected and again when it ends.
struct DetailView: View {
    let session: Session
    let id: Int64

    enum Side: String, CaseIterable, Identifiable {
        case request = "Request", response = "Response"
        var id: String { rawValue }
    }

    @State private var detail: Detail?
    @State private var loaded = false
    @State private var side: Side = .response

    /// The summary's state, so a pending request is fetched again once it ends.
    private var state: TransactionState? {
        session.transactions.last { $0.id == id }?.state
    }

    var body: some View {
        Group {
            if let detail {
                content(detail)
            } else if loaded {
                ContentUnavailableView("Request Gone", systemImage: "questionmark.circle",
                                       description: Text("The app no longer holds this request."))
            } else {
                ProgressView().controlSize(.small).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: state) {
            detail = await session.detail(id)
            loaded = true
        }
        .toolbar {
            ToolbarItem {
                Menu {
                    Button("Copy URL") { copy(detail?.url) }
                    Button("Copy as cURL") { copy(detail.map(Curl.command)) }
                    Divider()
                    Button("Copy Response Body") { copy(detail?.response?.body?.text) }
                        .disabled(detail?.response?.body?.text == nil)
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                .help("Copy the URL, a cURL command or the body")
                .disabled(detail == nil)
            }
        }
    }

    private func content(_ detail: Detail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Header(detail: detail)
                Picker("Side", selection: $side) {
                    ForEach(Side.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .controlSize(.large)
                if let exchange = side == .request ? detail.request : detail.response {
                    HeaderTable(headers: exchange.headers)
                    BodySection(content: exchange.body)
                } else {
                    Text(detail.state == .pending ? "Waiting for the response…" : "No response.")
                        .foregroundStyle(.secondary)
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func copy(_ text: String?) {
        guard let text else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }
}

private struct Header: View {
    let detail: Detail

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                StatusBadge(code: detail.code, state: detail.state)
                Text(detail.method)
                    .font(.system(.callout, design: .monospaced).weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(Format.path(detail.url))
                    .font(.system(.title3, design: .monospaced).weight(.medium))
                    .textSelection(.enabled)
                    .lineLimit(5)
                Text(Format.host(detail.url))
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            HStack(spacing: 0) {
                Metric("Duration", detail.ms.map(Format.duration) ?? "—")
                Metric("Size", Format.bytes(detail.response?.body?.totalBytes ?? detail.response?.body?.capturedBytes ?? 0))
                Metric("Protocol", detail.protocol ?? "—")
                Metric("Started", detail.startedAt.map {
                    Date(timeIntervalSince1970: Double($0) / 1000).formatted(.dateTime.hour().minute().second())
                } ?? "—")
            }
            .padding(.vertical, 10)
            .background(.quinary, in: .rect(cornerRadius: 10))
            if let error = detail.error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.red.opacity(0.1), in: .rect(cornerRadius: 10))
            }
        }
    }
}

private struct Metric: View {
    let title: String
    let value: String

    init(_ title: String, _ value: String) {
        self.title = title
        self.value = value
    }

    var body: some View {
        VStack(spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.callout.monospacedDigit().weight(.medium)).lineLimit(1)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct HeaderTable: View {
    let headers: [[String]]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Headers").font(.headline)
            if headers.isEmpty {
                Text("None").foregroundStyle(.secondary)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(headers.enumerated()), id: \.offset) { index, pair in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(pair.first ?? "")
                                .foregroundStyle(.secondary)
                                .frame(width: 140, alignment: .leading)
                                .lineLimit(2)
                            Text(pair.dropFirst().first ?? "")
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .font(.system(.callout, design: .monospaced))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        if index < headers.count - 1 { Divider().padding(.leading, 12) }
                    }
                }
                .background(.quinary, in: .rect(cornerRadius: 10))
            }
        }
    }
}

private struct BodySection: View {
    let content: WireBody?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("Body").font(.headline)
                Spacer()
                if let content {
                    Text(caption(content)).font(.caption).foregroundStyle(.secondary)
                }
            }
            if let content {
                if let reason = content.skippedReason {
                    Text("Not captured: \(reason)").foregroundStyle(.secondary)
                } else if let text = content.text {
                    let pretty = Format.prettyBody(text, contentType: content.contentType)
                    let isJSON = pretty != text || content.contentType?.contains("json") == true
                    Text(isJSON ? JSONHighlight.attributed(pretty) : AttributedString(pretty))
                        .font(.system(.callout, design: .monospaced))
                        .lineSpacing(2)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.quinary, in: .rect(cornerRadius: 10))
                } else {
                    Text("Binary, \(Format.bytes(content.capturedBytes))").foregroundStyle(.secondary)
                }
            } else {
                Text("None").foregroundStyle(.secondary)
            }
        }
    }

    private func caption(_ body: WireBody) -> String {
        var parts = [body.contentType ?? "", Format.bytes(body.totalBytes ?? body.capturedBytes)]
        if body.truncated { parts.append("cut at \(Format.bytes(body.capturedBytes))") }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// A cURL command for a request, single quotes escaped for a POSIX shell.
nonisolated enum Curl {
    static func command(_ detail: Detail) -> String {
        func quote(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }
        var parts = ["curl", "-X", detail.method, quote(detail.url)]
        for header in detail.request.headers where header.count == 2 {
            parts += ["-H", quote("\(header[0]): \(header[1])")]
        }
        if let text = detail.request.body?.text { parts += ["--data-raw", quote(text)] }
        return parts.joined(separator: " ")
    }
}
