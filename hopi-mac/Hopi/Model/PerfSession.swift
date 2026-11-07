import Foundation
import Observation

/// The app's performance over a `perf.tail` connection of its own: status, samples, frame seconds,
/// events. Monitors this viewer starts are held by that connection, so the app stops them when it
/// closes; starting or stopping opens the tail again with the new set.
@Observable
final class PerfSession {
    /// One point of a chart: a time and a value.
    struct Point: Identifiable, Hashable {
        let date: Date
        let value: Double
        var id: Date { date }
    }

    enum Status: Equatable {
        case connecting
        case live
        case unavailable(String)
        case closed(String?)
    }

    let target: Target
    private(set) var status: Status = .connecting
    private(set) var perf: PerfStatus?
    /// Monitors this viewer holds on its tail.
    private(set) var holding: Set<String> = []
    private(set) var cpu: [Point] = []
    private(set) var memory: [Point] = []
    private(set) var fps: [Point] = []
    private(set) var janky: [Point] = []
    /// Newest first.
    private(set) var events: [PerfEvent] = []
    private(set) var screen: String?

    @ObservationIgnored private var connection: LineConnection?
    @ObservationIgnored private var lastSeq: Int64 = 0
    private static let window = 300

    init(target: Target) {
        self.target = target
    }

    /// Whether this viewer may start and stop monitors.
    var canControl: Bool { perf?.remoteControl == true }

    func start() {
        open(holding: holding)
    }

    func stop() {
        connection?.close()
        connection = nil
    }

    /// Holds [monitors] from now on; an empty set holds none (those started elsewhere keep running).
    func hold(_ monitors: Set<String>) {
        holding = monitors
        open(holding: monitors)
    }

    func isRunning(_ monitor: String) -> Bool {
        perf?.running[monitor] != nil
    }

    private func open(holding monitors: Set<String>) {
        connection?.close()
        status = .connecting
        let connection = LineConnection(endpoint: target.endpoint)
        self.connection = connection
        var greeted = false
        connection.start { [weak self, weak connection] event in
            guard let self, let connection, self.connection === connection else { return }
            switch event {
            case .line(let data):
                if !greeted {
                    greeted = true
                    // perf.tail is a write: sent only once the hello proves this is the app's bridge.
                    guard let hello = try? JSONDecoder().decode(Hello.self, from: data),
                          self.target.endpoint.accepts(hello) else {
                        self.status = .unavailable("This is not \(self.target.app)'s bridge: its hello lacks the app's proof.")
                        connection.close()
                        return
                    }
                    connection.send([
                        "cmd": "perf.tail", "since": self.lastSeq, "samples": true, "fps": true,
                        "start": Array(monitors).sorted(),
                    ])
                    return
                }
                self.line(data)
            case .closed(let reason):
                if case .unavailable = self.status { return }
                self.status = .closed(reason)
            }
        }
        connection.send(["cmd": "hello"])
    }

    private func line(_ data: Data) {
        let decoder = JSONDecoder()
        guard let type = (try? decoder.decode(WireType.self, from: data))?.type else { return }
        switch type {
        case "perf.status":
            perf = try? decoder.decode(PerfStatus.self, from: data)
            status = .live
        case "perf.sample":
            guard let sample = try? decoder.decode(PerfSample.self, from: data) else { return }
            let date = Date(timeIntervalSince1970: Double(sample.at) / 1000)
            if let cpu = sample.cpu { append(&self.cpu, Point(date: date, value: cpu.process)) }
            if let resident = sample.memory?.resident {
                append(&memory, Point(date: date, value: Double(resident) / 1_048_576))
            }
        case "perf.frames":
            guard let second = try? decoder.decode(PerfFrames.self, from: data) else { return }
            let date = Date(timeIntervalSince1970: Double(second.sec))
            append(&fps, Point(date: date, value: Double(second.frames)))
            append(&janky, Point(date: date, value: Double(second.janky + second.frozen)))
            if let screen = second.screen { self.screen = screen }
        case "perf.event":
            guard let event = try? decoder.decode(PerfEvent.self, from: data) else { return }
            lastSeq = max(lastSeq, event.seq)
            if let index = events.firstIndex(where: { $0.seq == event.seq }) {
                events[index] = event // an ongoing hang that ended
            } else {
                events.insert(event, at: 0)
                if events.count > 500 { events.removeLast() }
            }
        case "error":
            let error = try? decoder.decode(WireError.self, from: data)
            status = .unavailable(error?.message ?? "The app refused perf.tail.")
            connection?.close()
        default:
            break
        }
    }

    private func append(_ points: inout [Point], _ point: Point) {
        if let last = points.last, last.date >= point.date { return }
        points.append(point)
        if points.count > Self.window { points.removeFirst(points.count - Self.window) }
    }
}
