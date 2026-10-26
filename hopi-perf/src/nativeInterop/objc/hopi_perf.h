// The iOS performance monitors' native part (docs/design/perf-monitors.md, "Hangs", "Frames",
// "CPU", "Memory"). Plain C declarations, so the cinterop binding exposes no Objective-C class.
//
// The main run loop's two observers publish the main thread's busy state as one packed 64-bit
// value (the high 24 bits a sequence number, the low 40 bits the unit's start in milliseconds of
// CLOCK_UPTIME_RAW, 0 while idle), exactly as core's BusyState does on Android, so core's
// HangDetector reads it unchanged. The observers never enter Kotlin except for a unit over the
// stall threshold. The stack capture suspends the main thread, reads its registers, walks its
// frame pointers into a caller's array and resumes it, and between suspend and resume nothing
// allocates, locks, logs or enters Kotlin.
#ifndef HOPI_PERF_H
#define HOPI_PERF_H

#include <stdbool.h>
#include <stdint.h>

// ---- clocks

/** CLOCK_UPTIME_RAW in milliseconds: the clock of the packed state's starts. */
int64_t hopi_perf_now_ms(void);

/** CLOCK_UPTIME_RAW in nanoseconds: the frames' and pauses' monotonic clock. */
int64_t hopi_perf_now_ns(void);

/** The calling thread's own CPU time (CLOCK_THREAD_CPUTIME_ID), nanoseconds. */
int64_t hopi_perf_thread_cpu_ns(void);

// ---- main-thread busy state (one per process)

/** Called on the main thread for a unit longer than the stall threshold, when it ends. */
typedef void (*hopi_perf_stall_callback)(int64_t duration_ns, int64_t end_ns);

/**
 * Adds the two observers to the main run loop for the common modes (any thread; CFRunLoop is
 * thread-safe), once: a later call while they are installed changes nothing. Also reads the main
 * thread's mach port and stack top for the capture. Returns false when they could not be created.
 */
bool hopi_perf_observers_install(void);

/** Removes and invalidates the observers (any thread); the state reads idle afterwards. */
void hopi_perf_observers_remove(void);

bool hopi_perf_observers_installed(void);

/** A unit at least threshold_ns long calls callback on the main thread when it ends; 0: no stall reports. */
void hopi_perf_set_stall(int64_t threshold_ns, hopi_perf_stall_callback callback);

/** The packed state. */
uint64_t hopi_perf_busy_load(void);

/** A unit starts now (the observers; also the heartbeat, which iOS does not use). */
void hopi_perf_busy_begin(int64_t now_ms);

/** The unit ends now. */
void hopi_perf_busy_end(int64_t now_ms);

/** Watchdog: about to sleep with no timeout; false when the main thread turned busy meanwhile. */
bool hopi_perf_watchdog_park(void);

void hopi_perf_watchdog_unparked(void);

void hopi_perf_set_hang_ongoing(bool ongoing);

/** When the unit that was hanging ended (0: not seen), and resets it. */
int64_t hopi_perf_take_last_end(void);

/**
 * A watchdog's own semaphore, one per hang-monitor start, so a stopped watchdog that has not woken
 * yet never shares a wake with the next one. attach makes it the one the observers signal (an
 * idle-to-busy transition while parked, a hang's end); detach stops that. It has two owners, the
 * watchdog's thread and the monitor that stops it, and each calls release once (after its last
 * use); the second release frees it. NULL when out of memory.
 */
void *hopi_perf_watchdog_create(void);

void hopi_perf_watchdog_attach(void *watchdog);

void hopi_perf_watchdog_detach(void *watchdog);

void hopi_perf_watchdog_release(void *watchdog);

/** Sleeps until a wake. */
void hopi_perf_watchdog_await(void *watchdog);

/** Sleeps at most nanos; true when woken. */
bool hopi_perf_watchdog_doze(void *watchdog, int64_t nanos);

/** Wakes that watchdog (any thread). */
void hopi_perf_watchdog_wake(void *watchdog);

/** How many times the watchdog's untimed await returned (tests: none while the main thread is idle). */
int64_t hopi_perf_watchdog_wakeups(void);

/** Observer callbacks run so far (tests). */
int64_t hopi_perf_observer_calls(void);

// ---- stack capture

/** Bits of a return address kept after stripping pointer-authentication bits. */
#define HOPI_PERF_PAC_MASK 0x0000000FFFFFFFFFULL

/** The Swift async-frame flag in a saved frame pointer. */
#define HOPI_PERF_ASYNC_FP_BIT (1ULL << 60)

/**
 * Walks a frame-pointer chain starting at fp into out[count..max), reading a frame directly only
 * while fp (async flag cleared) lies in [stack_lo, stack_hi - 16], is 16-byte aligned and strictly
 * above the previous one; anything else ends the walk. Return addresses are stripped with
 * HOPI_PERF_PAC_MASK; a zero one ends the walk. Returns the new count. Pure: tests feed it a
 * synthetic chain.
 */
int32_t hopi_perf_walk(uint64_t fp, uint64_t stack_lo, uint64_t stack_hi, uint64_t *out, int32_t count, int32_t max);

/**
 * Suspends the main thread, reads pc, lr, fp and sp, walks into out (at most max addresses,
 * innermost first, pointer-auth bits stripped) and resumes it. suspended_ns receives the time the
 * main thread was suspended. Returns the number of addresses, or -1 when the observers were never
 * installed, the caller is the main thread, or the kernel refused.
 */
int32_t hopi_perf_capture_main(uint64_t *out, int32_t max, int64_t *suspended_ns);

/**
 * Names addr ("image symbol + offset") into buf with dladdr, after the capture (dladdr takes the
 * dyld lock, so never between suspend and resume); returns the length written, 0 when unknown.
 * return_address: addr follows a call, so the lookup uses addr - 1 (a call that is a function's
 * last instruction returns past its end).
 */
int32_t hopi_perf_symbolicate(uint64_t addr, bool return_address, char *buf, int32_t length);

/** Whether a debugger is attached (sysctl P_TRACED): one call per detection. */
bool hopi_perf_debugger_attached(void);

// ---- CPU and memory

/** The process's CPU time, nanoseconds: terminated threads (MACH_TASK_BASIC_INFO) plus live ones (TASK_THREAD_TIMES_INFO); -1 on error. */
int64_t hopi_perf_process_cpu_ns(void);

/** TASK_VM_INFO.phys_footprint, bytes (what jetsam counts); -1 on error. */
int64_t hopi_perf_footprint(void);

/** os_proc_available_memory(), bytes; 0 where the OS gives no limit (the simulator). */
int64_t hopi_perf_available_memory(void);

/** One thread of the per-thread scan. */
typedef struct {
    uint64_t tid;
    int64_t user_us;
    int64_t system_us;
    /** THREAD_BASIC_INFO.cpu_usage: the kernel's decayed usage, of TH_USAGE_SCALE (1000). */
    int32_t cpu_usage;
    int32_t idle;
    char name[64];
} hopi_perf_thread_info;

/**
 * task_threads, then THREAD_BASIC_INFO, THREAD_IDENTIFIER_INFO and THREAD_EXTENDED_INFO per
 * thread into out (at most max); every thread port is deallocated and the array freed in the same
 * call. Returns the number written, -1 on error.
 */
int32_t hopi_perf_threads(hopi_perf_thread_info *out, int32_t max);

/** Send rights and other names in the task's port space (tests: no leak over many scans). */
int32_t hopi_perf_port_count(void);

// ---- memory events

/** level: 1 for UIApplicationDidReceiveMemoryWarningNotification, 2 pressure warn, 4 pressure critical. */
typedef void (*hopi_perf_memory_callback)(int32_t level);

#define HOPI_PERF_MEMORY_WARNING 1
#define HOPI_PERF_MEMORY_PRESSURE_WARN 2
#define HOPI_PERF_MEMORY_PRESSURE_CRITICAL 4

/** The memory-warning notification observer and a memory-pressure dispatch source on a utility queue. */
void hopi_perf_memory_events_start(hopi_perf_memory_callback callback);

/** Removes the observer and cancels the source. */
void hopi_perf_memory_events_stop(void);

// ---- frames, tier 2: display-link cadence (explicit request only)

/** Per display-link callback, on the main thread: the interval since the previous one, the vsync period, missed vsyncs. */
typedef void (*hopi_perf_vsync_callback)(int64_t now_ns, int64_t interval_ns, int64_t period_ns, int32_t missed);

/** Creates the display link on the main run loop (hops to the main queue), paused in the background. */
void hopi_perf_cadence_start(hopi_perf_vsync_callback callback);

/** Invalidates it (hops to the main queue). */
void hopi_perf_cadence_stop(void);

bool hopi_perf_cadence_running(void);

// ---- the main screen

/** UIScreen.mainScreen.maximumFramesPerSecond; main thread only; 60 when unknown. */
int32_t hopi_perf_max_fps_main_thread(void);

/** Whether the app's Info.plist sets CADisableMinimumFrameDurationOnPhone (any thread). */
bool hopi_perf_high_rate_allowed(void);

#endif
