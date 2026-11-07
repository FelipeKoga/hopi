import Charts
import SwiftUI

/// The app's performance, live: CPU, memory, frames per second and janky frames as charts of the
/// last five minutes, and the events (hangs, janky frames, stalls, memory signals) below.
struct PerfView: View {
    let perf: PerfSession
    @State private var selectedEvent: PerfEvent.ID?

    private static let monitors = ["frames", "hangs", "cpu", "memory"]

    static func title(_ monitor: String) -> String {
        monitor == "cpu" ? "CPU" : monitor.capitalized
    }

    var body: some View {
        Group {
            switch perf.status {
            case .unavailable(let message):
                ContentUnavailableView("No Performance Data", systemImage: "gauge.with.dots.needle.0percent",
                                       description: Text(message))
            case .closed(let reason):
                ContentUnavailableView {
                    Label("Disconnected", systemImage: "bolt.horizontal.circle")
                } description: {
                    Text(reason ?? "The app closed the performance stream.")
                } actions: {
                    Button("Reconnect") { perf.start() }.buttonStyle(.glassProminent)
                }
            default:
                content
            }
        }
        .inspector(isPresented: Binding(get: { selectedEvent != nil }, set: { if !$0 { selectedEvent = nil } })) {
            Group {
                if let event = perf.events.first(where: { $0.id == selectedEvent }) {
                    EventDetail(event: event)
                }
            }
            .inspectorColumnWidth(min: 320, ideal: 420, max: 720)
        }
        .toolbar {
            ToolbarItem {
                Button {
                    perf.start()
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .help("Read the monitors' state again")
            }
        }
    }

    private var content: some View {
        VStack(spacing: 0) {
            monitorBar
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            Divider()
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 14) {
                        ChartCard(title: "CPU", unit: "%", points: perf.cpu, format: "%.0f", monitor: "cpu", perf: perf)
                        ChartCard(title: "Memory", unit: "MB", points: perf.memory, format: "%.0f", monitor: "memory", perf: perf)
                        ChartCard(title: "Frames per second", unit: "fps", points: perf.fps, format: "%.0f", monitor: "frames", perf: perf)
                        ChartCard(title: "Janky frames", unit: "per s", points: perf.janky, format: "%.0f", monitor: "frames", perf: perf,
                                  tint: .orange)
                    }
                    events
                }
                .padding(16)
            }
        }
    }

    private var monitorBar: some View {
        HStack(spacing: 8) {
            if let status = perf.perf {
                ForEach(Self.monitors.filter(status.available.contains), id: \.self) { monitor in
                    MonitorChip(name: monitor, running: perf.isRunning(monitor), held: perf.holding.contains(monitor),
                                enabled: perf.canControl) {
                        var held = perf.holding
                        if held.contains(monitor) { held.remove(monitor) } else { held.insert(monitor) }
                        perf.hold(held)
                    }
                }
                Spacer()
                if let screen = perf.screen {
                    Label(screen, systemImage: "rectangle.on.rectangle")
                        .font(.callout).foregroundStyle(.secondary).lineLimit(1)
                }
                if !perf.canControl {
                    Text("The app controls its monitors")
                        .font(.callout).foregroundStyle(.secondary)
                }
            } else {
                ProgressView().controlSize(.small)
                Text("Connecting…").foregroundStyle(.secondary)
                Spacer()
            }
        }
    }

    private var events: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Events").font(.headline)
            if perf.events.isEmpty {
                Text("Hangs, janky frames, stalls and memory warnings show up here.")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 60)
                    .background(.quinary, in: .rect(cornerRadius: 10))
            } else {
                VStack(spacing: 0) {
                    ForEach(perf.events.prefix(200)) { event in
                        EventRow(event: event, selected: event.id == selectedEvent) { selectedEvent = event.id }
                        if event.id != perf.events.prefix(200).last?.id { Divider().padding(.leading, 12) }
                    }
                }
                .background(.quinary, in: .rect(cornerRadius: 10))
            }
        }
    }
}

/// A monitor and whether it runs; a click starts or stops the ones this viewer holds.
private struct MonitorChip: View {
    let name: String
    let running: Bool
    let held: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Group {
            if held {
                Button(action: action) { label }.buttonStyle(.glassProminent)
            } else {
                Button(action: action) { label }.buttonStyle(.glass)
            }
        }
        .disabled(!enabled)
        .accessibilityIdentifier("monitor:\(name)")
        .help(running ? (held ? "Running, started here: click to stop" : "Running") : "Stopped: click to start")
    }
}

extension MonitorChip {
    fileprivate var label: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(running ? AnyShapeStyle(.green) : AnyShapeStyle(.tertiary))
                .frame(width: 7, height: 7)
            Text(PerfView.title(name))
        }
        .padding(.horizontal, 4)
    }
}

/// One series over time, its latest value large.
private struct ChartCard: View {
    let title: String
    let unit: String
    let points: [PerfSession.Point]
    let format: String
    let monitor: String
    let perf: PerfSession
    var tint: Color = .accentColor

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                if let last = points.last {
                    Text(String(format: format, last.value))
                        .font(.title2.monospacedDigit().weight(.semibold))
                    Text(unit).font(.caption).foregroundStyle(.secondary)
                }
            }
            if points.count < 2 {
                Text(perf.isRunning(monitor) ? "Waiting for data…" : "\(PerfView.title(monitor)) is off")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Chart(points) { point in
                    AreaMark(x: .value("Time", point.date), y: .value(title, point.value))
                        .foregroundStyle(tint.opacity(0.12))
                    LineMark(x: .value("Time", point.date), y: .value(title, point.value))
                        .foregroundStyle(tint)
                        .lineStyle(StrokeStyle(lineWidth: 1.5))
                }
                .chartXAxis {
                    AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                        AxisGridLine()
                        AxisValueLabel(format: .dateTime.hour().minute().second())
                    }
                }
                .chartYAxis { AxisMarks(position: .trailing, values: .automatic(desiredCount: 3)) }
            }
        }
        .padding(14)
        .frame(height: 190)
        .background(.quinary, in: .rect(cornerRadius: 12))
        .accessibilityIdentifier("chart:\(title)")
    }
}

private struct EventRow: View {
    let event: PerfEvent
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: EventDetail.symbol(event))
                    .foregroundStyle(EventDetail.tint(event))
                    .frame(width: 18)
                Text(EventDetail.title(event)).fontWeight(.medium)
                if let screen = event.screen {
                    Text(screen).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Text(Date(timeIntervalSince1970: Double(event.at) / 1000).formatted(.dateTime.hour().minute().second()))
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(selected ? AnyShapeStyle(.selection.opacity(0.3)) : AnyShapeStyle(.clear))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
    }
}

/// An event in the inspector: what happened, for how long, and the main thread's stacks.
private struct EventDetail: View {
    let event: PerfEvent

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Label(Self.title(event), systemImage: Self.symbol(event))
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(Self.tint(event))
                Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                    row("Kind", event.kind)
                    row("At", Date(timeIntervalSince1970: Double(event.at) / 1000).formatted(.dateTime.hour().minute().second()))
                    if let ms = event.ms { row("Duration", Format.duration(Int64(ms))) }
                    if let state = event.state { row("State", state) }
                    if let screen = event.screen { row("Screen", screen) }
                    if let level = event.level { row("Level", level) }
                }
                .font(.callout)
                if let detail = event.detail ?? event.description {
                    Text(detail).textSelection(.enabled)
                }
                if let stacks = event.stacks, !stacks.isEmpty {
                    ForEach(Array(stacks.enumerated()), id: \.offset) { index, stack in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(stacks.count == 1 ? "Main thread" : "Main thread, capture \(index + 1)").font(.headline)
                            Text(stack.joined(separator: "\n"))
                                .font(.system(.caption, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                                .background(.quinary, in: .rect(cornerRadius: 8))
                        }
                    }
                }
                if let trace = event.trace {
                    Text(trace)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                        .padding(10)
                        .background(.quinary, in: .rect(cornerRadius: 8))
                }
            }
            .padding(18)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        GridRow {
            Text(title).foregroundStyle(.secondary)
            Text(value).textSelection(.enabled)
        }
    }

    static func title(_ event: PerfEvent) -> String {
        let duration = event.ms.map { " · " + Format.duration(Int64($0)) } ?? ""
        switch event.kind {
        case "hang": return (event.state == "ongoing" ? "Hang (ongoing)" : "Hang") + duration
        case "jank": return "Janky frame" + duration
        case "stall": return "Stall" + duration
        case "memory": return "Memory: " + (event.event ?? event.level ?? "signal")
        case "anr": return "Earlier ANR"
        default: return event.kind.capitalized + duration
        }
    }

    static func symbol(_ event: PerfEvent) -> String {
        switch event.kind {
        case "hang", "anr": "hourglass"
        case "jank": "film"
        case "stall": "tortoise"
        case "memory": "memorychip"
        default: "circle"
        }
    }

    static func tint(_ event: PerfEvent) -> Color {
        switch event.kind {
        case "hang", "anr": .red
        case "jank", "stall": .orange
        default: .secondary
        }
    }
}
