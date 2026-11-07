import SwiftUI

/// One request, fetched with `get` when selected. The request/response switch floats over the
/// content in glass; everything it switches between stays solid.
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
        session.transactions.first { $0.id == id }?.state
    }

    var body: some View {
        Group {
            if let detail {
                content(detail)
            } else if loaded {
                ContentUnavailableView("Request Gone", systemImage: "questionmark.circle",
                                       description: Text("The app no longer holds this request."))
            } else {
                ProgressView().controlSize(.small)
            }
        }
        .task(id: state) {
            detail = await session.detail(id)
            loaded = true
        }
    }

    private func content(_ detail: Detail) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header(detail)
                let exchange = side == .request ? detail.request : detail.response
                if let exchange {
                    HeaderList(headers: exchange.headers)
                    BodyView(content: exchange.body)
                } else {
                    Text("No response yet.").foregroundStyle(.secondary)
                }
            }
            .padding(20)
            .padding(.bottom, 56)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .overlay(alignment: .bottom) {
            GlassEffectContainer {
                Picker("Side", selection: $side) {
                    ForEach(Side.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(width: 220)
                .padding(6)
                .glassEffect(.regular, in: .capsule)
            }
            .padding(.bottom, 14)
        }
    }

    private func header(_ detail: Detail) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(detail.method) \(detail.url)")
                .font(.title3.monospaced())
                .textSelection(.enabled)
                .lineLimit(4)
            HStack(spacing: 14) {
                if let code = detail.code {
                    Text(String(code)).fontWeight(.semibold)
                        .foregroundStyle(StatusText.color(code: code, state: detail.state))
                }
                if let ms = detail.ms { Text(Format.duration(ms)) }
                if let proto = detail.protocol { Text(proto) }
                if let startedAt = detail.startedAt {
                    Text(Date(timeIntervalSince1970: Double(startedAt) / 1000), format: .dateTime.hour().minute().second())
                }
            }
            .font(.callout.monospacedDigit())
            .foregroundStyle(.secondary)
            if let error = detail.error {
                Text(error).foregroundStyle(.red).textSelection(.enabled)
            }
        }
    }

}

private struct HeaderList: View {
    let headers: [[String]]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Headers").font(.headline)
            if headers.isEmpty {
                Text("None").foregroundStyle(.secondary)
            } else {
                Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: 16, verticalSpacing: 4) {
                    ForEach(Array(headers.enumerated()), id: \.offset) { _, pair in
                        GridRow {
                            Text(pair.first ?? "").foregroundStyle(.secondary)
                            Text(pair.dropFirst().first ?? "").textSelection(.enabled)
                        }
                    }
                }
                .font(.callout.monospaced())
            }
        }
    }
}

private struct BodyView: View {
    let content: WireBody?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Body").font(.headline)
                Spacer()
                if let body = content {
                    Text(caption(body)).font(.caption).foregroundStyle(.secondary)
                }
            }
            if let body = content {
                if let reason = body.skippedReason {
                    Text("Not captured: \(reason)").foregroundStyle(.secondary)
                } else if let text = body.text {
                    Text(Format.prettyBody(text, contentType: body.contentType))
                        .font(.callout.monospaced())
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 10))
                } else {
                    Text("Binary, \(Format.bytes(body.capturedBytes))").foregroundStyle(.secondary)
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
