// The iOS performance monitors' native part; see hopi_perf.h and docs/design/perf-monitors.md.
#import "hopi_perf.h"

#import <Foundation/Foundation.h>
#import <QuartzCore/QuartzCore.h>
#import <UIKit/UIKit.h>
#include <CoreFoundation/CoreFoundation.h>
#include <dispatch/dispatch.h>
#include <dlfcn.h>
#include <mach/mach.h>
#include <os/lock.h>
#include <os/proc.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysctl.h>
#include <time.h>
#include <unistd.h>

// Exported by libsystem_pthread (libSystem.tbd lists it) but declared only in its private header.
extern pthread_t pthread_main_thread_np(void);

// ---- clocks

int64_t hopi_perf_now_ns(void) {
    return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

int64_t hopi_perf_now_ms(void) {
    return hopi_perf_now_ns() / 1000000;
}

int64_t hopi_perf_thread_cpu_ns(void) {
    return (int64_t)clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID);
}

// ---- main-thread busy state

// The same packing as core's BusyState: 24-bit sequence number, 40-bit start (0: idle).
#define SEQ_BITS 24
#define START_BITS 40
#define SEQ_MASK ((1ULL << SEQ_BITS) - 1)
#define START_MASK ((1ULL << START_BITS) - 1)

static _Atomic uint64_t g_packed;
static _Atomic bool g_parked;
static _Atomic bool g_hang_ongoing;
static _Atomic int64_t g_last_end;
static _Atomic int64_t g_wakeups;
static _Atomic int64_t g_observer_calls;
// The attached watchdog's semaphore; the lock is taken only on the rare signalling paths and by
// attach and detach, never per observer callback.
static dispatch_semaphore_t g_watchdog;
static os_unfair_lock g_watchdog_lock = OS_UNFAIR_LOCK_INIT;

// Stall reports (frames, tier 1): written by the Kotlin side under its lock, read on the main thread.
static _Atomic int64_t g_stall_ns;
static _Atomic(hopi_perf_stall_callback) g_stall_callback;

// Written by the observers on the main thread, cleared by install and remove (any thread), so a
// unit that began before a remove never reaches the next install's first stall check: the
// running unit's start in nanoseconds (0: idle or unknown).
static _Atomic int64_t g_unit_start_ns;

static CFRunLoopObserverRef g_begin_observer;
static CFRunLoopObserverRef g_end_observer;
static _Atomic bool g_installed;

// Read once at install, from any thread, for the capture.
static _Atomic thread_t g_main_port;
static _Atomic uint64_t g_stack_hi;

static uint64_t pack(uint64_t seq, int64_t start_ms) {
    uint64_t start = 0;
    if (start_ms != 0) {
        start = (uint64_t)start_ms & START_MASK;
        if (start == 0) start = 1;
    }
    return ((seq & SEQ_MASK) << START_BITS) | start;
}

uint64_t hopi_perf_busy_load(void) {
    return atomic_load(&g_packed);
}

static void signal_watchdog(void) {
    os_unfair_lock_lock(&g_watchdog_lock);
    if (g_watchdog != nil) dispatch_semaphore_signal(g_watchdog);
    os_unfair_lock_unlock(&g_watchdog_lock);
}

// A watchdog's semaphore with two owners: its thread, and the monitor that stops it (whose wake
// may land after the thread saw the stop and ended). The last release frees it.
typedef struct {
    void *semaphore;
    _Atomic int32_t owners;
} hopi_perf_watchdog;

static dispatch_semaphore_t watchdog_semaphore(void *watchdog) {
    return (__bridge dispatch_semaphore_t)((hopi_perf_watchdog *)watchdog)->semaphore;
}

void *hopi_perf_watchdog_create(void) {
    hopi_perf_watchdog *watchdog = calloc(1, sizeof(hopi_perf_watchdog));
    if (watchdog == NULL) return NULL;
    watchdog->semaphore = (__bridge_retained void *)dispatch_semaphore_create(0);
    atomic_store(&watchdog->owners, 2);
    return watchdog;
}

void hopi_perf_watchdog_attach(void *watchdog) {
    atomic_store(&g_parked, false);
    atomic_store(&g_hang_ongoing, false);
    atomic_store(&g_last_end, 0);
    os_unfair_lock_lock(&g_watchdog_lock);
    g_watchdog = watchdog_semaphore(watchdog);
    os_unfair_lock_unlock(&g_watchdog_lock);
}

void hopi_perf_watchdog_detach(void *watchdog) {
    os_unfair_lock_lock(&g_watchdog_lock);
    if (g_watchdog == watchdog_semaphore(watchdog)) g_watchdog = nil;
    os_unfair_lock_unlock(&g_watchdog_lock);
}

void hopi_perf_watchdog_release(void *watchdog) {
    hopi_perf_watchdog_detach(watchdog);
    hopi_perf_watchdog *owned = watchdog;
    if (atomic_fetch_sub(&owned->owners, 1) != 1) return;
    CFBridgingRelease(owned->semaphore);
    free(owned);
}

void hopi_perf_watchdog_wake(void *watchdog) {
    dispatch_semaphore_signal(watchdog_semaphore(watchdog));
}

// The unit that ran until now ended: idle, and the watchdog of a hang is told when.
static void busy_end(int64_t now_ms) {
    uint64_t packed = atomic_load(&g_packed);
    if ((packed & START_MASK) == 0) return;
    atomic_store(&g_packed, pack(packed >> START_BITS, 0));
    if (atomic_load(&g_hang_ongoing)) {
        atomic_store(&g_last_end, now_ms);
        signal_watchdog();
    }
}

void hopi_perf_busy_begin(int64_t now_ms) {
    busy_end(now_ms);
    uint64_t seq = ((atomic_load(&g_packed) >> START_BITS) + 1) & SEQ_MASK;
    atomic_store(&g_packed, pack(seq, now_ms > 0 ? now_ms : 1));
    // One release per idle-to-busy transition, and only while the watchdog sleeps with no timeout.
    bool parked = true;
    if (atomic_load(&g_parked) && atomic_compare_exchange_strong(&g_parked, &parked, false)) signal_watchdog();
}

void hopi_perf_busy_end(int64_t now_ms) {
    busy_end(now_ms);
}

bool hopi_perf_watchdog_park(void) {
    atomic_store(&g_parked, true);
    if ((atomic_load(&g_packed) & START_MASK) != 0) {
        atomic_store(&g_parked, false);
        return false;
    }
    return true;
}

void hopi_perf_watchdog_unparked(void) {
    atomic_store(&g_parked, false);
}

void hopi_perf_set_hang_ongoing(bool ongoing) {
    atomic_store(&g_hang_ongoing, ongoing);
}

int64_t hopi_perf_take_last_end(void) {
    return atomic_exchange(&g_last_end, 0);
}

void hopi_perf_watchdog_await(void *watchdog) {
    dispatch_semaphore_wait(watchdog_semaphore(watchdog), DISPATCH_TIME_FOREVER);
    atomic_fetch_add(&g_wakeups, 1);
}

bool hopi_perf_watchdog_doze(void *watchdog, int64_t nanos) {
    return dispatch_semaphore_wait(watchdog_semaphore(watchdog), dispatch_time(DISPATCH_TIME_NOW, nanos)) == 0;
}

int64_t hopi_perf_watchdog_wakeups(void) {
    return atomic_load(&g_wakeups);
}

int64_t hopi_perf_observer_calls(void) {
    return atomic_load(&g_observer_calls);
}

// Main thread: the unit that started at g_unit_start_ns ended at now_ns. The state turns idle
// first, so the stall callback (which enters Kotlin) is never counted as the app's work.
static void unit_end(int64_t now_ns) {
    busy_end(now_ns / 1000000);
    int64_t start = atomic_exchange_explicit(&g_unit_start_ns, 0, memory_order_relaxed);
    int64_t threshold = atomic_load_explicit(&g_stall_ns, memory_order_relaxed);
    if (start == 0 || threshold <= 0 || now_ns - start < threshold) return;
    hopi_perf_stall_callback callback = atomic_load(&g_stall_callback);
    if (callback != NULL) callback(now_ns - start, now_ns);
}

static void unit_begin(int64_t now_ns) {
    atomic_store_explicit(&g_unit_start_ns, now_ns, memory_order_relaxed);
    hopi_perf_busy_begin(now_ns / 1000000);
}

// Lowest order, AfterWaiting | BeforeTimers | Exit: a unit starts on waking, every iteration
// that loops without sleeping starts a new one at BeforeTimers, and the code that runs after a run
// loop returns (a nested loop's caller, a test body) is a unit of its own.
static void on_begin(CFRunLoopObserverRef observer, CFRunLoopActivity activity, void *info) {
    (void)observer;
    (void)activity;
    (void)info;
    atomic_fetch_add_explicit(&g_observer_calls, 1, memory_order_relaxed);
    int64_t now = hopi_perf_now_ns();
    unit_end(now);
    unit_begin(hopi_perf_now_ns());
}

// Highest order, BeforeWaiting: Core Animation's commit (order 2000000) counts as busy.
static void on_end(CFRunLoopObserverRef observer, CFRunLoopActivity activity, void *info) {
    (void)observer;
    (void)activity;
    (void)info;
    atomic_fetch_add_explicit(&g_observer_calls, 1, memory_order_relaxed);
    unit_end(hopi_perf_now_ns());
}

bool hopi_perf_observers_install(void) {
    if (atomic_load(&g_installed)) return true;
    pthread_t main = pthread_main_thread_np();
    // pthread_mach_thread_np adds no port reference.
    atomic_store(&g_main_port, pthread_mach_thread_np(main));
    atomic_store(&g_stack_hi, (uint64_t)(uintptr_t)pthread_get_stackaddr_np(main));
    CFRunLoopObserverRef begin = CFRunLoopObserverCreate(
        kCFAllocatorDefault, kCFRunLoopAfterWaiting | kCFRunLoopBeforeTimers | kCFRunLoopExit, true, LONG_MIN, on_begin, NULL);
    CFRunLoopObserverRef end = CFRunLoopObserverCreate(kCFAllocatorDefault, kCFRunLoopBeforeWaiting, true, LONG_MAX, on_end, NULL);
    if (begin == NULL || end == NULL) {
        if (begin != NULL) CFRelease(begin);
        if (end != NULL) CFRelease(end);
        return false;
    }
    atomic_store(&g_unit_start_ns, 0);
    CFRunLoopRef loop = CFRunLoopGetMain();
    CFRunLoopAddObserver(loop, begin, kCFRunLoopCommonModes);
    CFRunLoopAddObserver(loop, end, kCFRunLoopCommonModes);
    g_begin_observer = begin;
    g_end_observer = end;
    atomic_store(&g_installed, true);
    return true;
}

void hopi_perf_observers_remove(void) {
    if (!atomic_load(&g_installed)) return;
    CFRunLoopRef loop = CFRunLoopGetMain();
    CFRunLoopRemoveObserver(loop, g_begin_observer, kCFRunLoopCommonModes);
    CFRunLoopRemoveObserver(loop, g_end_observer, kCFRunLoopCommonModes);
    CFRunLoopObserverInvalidate(g_begin_observer);
    CFRunLoopObserverInvalidate(g_end_observer);
    CFRelease(g_begin_observer);
    CFRelease(g_end_observer);
    g_begin_observer = NULL;
    g_end_observer = NULL;
    atomic_store(&g_installed, false);
    atomic_store(&g_unit_start_ns, 0);
    uint64_t packed = atomic_load(&g_packed);
    atomic_store(&g_packed, pack(packed >> START_BITS, 0));
}

bool hopi_perf_observers_installed(void) {
    return atomic_load(&g_installed);
}

void hopi_perf_set_stall(int64_t threshold_ns, hopi_perf_stall_callback callback) {
    atomic_store(&g_stall_callback, callback);
    atomic_store(&g_stall_ns, threshold_ns);
}

// ---- stack capture

int32_t hopi_perf_walk(uint64_t fp, uint64_t stack_lo, uint64_t stack_hi, uint64_t *out, int32_t count, int32_t max) {
    uint64_t previous = 0;
    while (count < max) {
        fp &= ~HOPI_PERF_ASYNC_FP_BIT;
        if (fp == 0 || fp < stack_lo || stack_hi < 16 || fp > stack_hi - 16 || (fp & 0xF) != 0) break;
        // Strictly increasing: also ends any cycle in a corrupt chain.
        if (previous != 0 && fp <= previous) break;
        const uint64_t *frame = (const uint64_t *)(uintptr_t)fp;
        uint64_t ret = frame[1] & HOPI_PERF_PAC_MASK;
        if (ret == 0) break;
        out[count++] = ret;
        previous = fp;
        fp = frame[0];
    }
    return count;
}

int32_t hopi_perf_capture_main(uint64_t *out, int32_t max, int64_t *suspended_ns) {
    *suspended_ns = 0;
    thread_t main = atomic_load(&g_main_port);
    uint64_t stack_hi = atomic_load(&g_stack_hi);
    if (main == MACH_PORT_NULL || pthread_main_np() || max < 2) return -1;
    arm_thread_state64_t state;
    mach_msg_type_number_t state_count = ARM_THREAD_STATE64_COUNT;
    int32_t count = 0;
    uint64_t lr = 0;
    int64_t start = hopi_perf_now_ns();
    if (thread_suspend(main) != KERN_SUCCESS) return -1;
    // From here to thread_resume: no allocation, no lock, no log, no Kotlin.
    kern_return_t got = thread_get_state(main, ARM_THREAD_STATE64, (thread_state_t)&state, &state_count);
    if (got == KERN_SUCCESS) {
        uint64_t pc = (uint64_t)arm_thread_state64_get_pc(state) & HOPI_PERF_PAC_MASK;
        lr = (uint64_t)arm_thread_state64_get_lr(state) & HOPI_PERF_PAC_MASK;
        uint64_t fp = (uint64_t)arm_thread_state64_get_fp(state);
        uint64_t sp = (uint64_t)arm_thread_state64_get_sp(state);
        out[count++] = pc;
        // The frames above sp, up to the stack's top: a frame never lies below the stack pointer.
        count = hopi_perf_walk(fp, sp, stack_hi, out, count, max);
    }
    thread_resume(main);
    *suspended_ns = hopi_perf_now_ns() - start;
    if (got != KERN_SUCCESS) return -1;
    // A leaf function that pushed no frame returns through lr, which the walk then misses; one
    // that did pushed lr as its frame's return address, the walk's first.
    if (lr != 0 && (count < 2 || out[1] != lr)) {
        if (count == max) count--;
        memmove(&out[2], &out[1], sizeof(uint64_t) * (size_t)(count - 1));
        out[1] = lr;
        count++;
    }
    return count;
}

int32_t hopi_perf_symbolicate(uint64_t addr, bool return_address, char *buf, int32_t length) {
    if (length <= 0 || addr == 0) return 0;
    uint64_t lookup = return_address ? addr - 1 : addr;
    Dl_info info;
    if (dladdr((const void *)(uintptr_t)lookup, &info) == 0) return 0;
    const char *image = info.dli_fname != NULL ? strrchr(info.dli_fname, '/') : NULL;
    image = image != NULL ? image + 1 : (info.dli_fname != NULL ? info.dli_fname : "?");
    int written;
    if (info.dli_sname != NULL && info.dli_saddr != NULL) {
        written = snprintf(buf, (size_t)length, "%s %s + %llu", image, info.dli_sname,
                           (unsigned long long)(addr - (uint64_t)(uintptr_t)info.dli_saddr));
    } else {
        written = snprintf(buf, (size_t)length, "%s 0x%llx + %llu", image, (unsigned long long)(uintptr_t)info.dli_fbase,
                           (unsigned long long)(addr - (uint64_t)(uintptr_t)info.dli_fbase));
    }
    if (written < 0) return 0;
    return written < length ? written : length - 1;
}

bool hopi_perf_debugger_attached(void) {
    struct kinfo_proc info;
    memset(&info, 0, sizeof(info));
    size_t size = sizeof(info);
    int mib[4] = {CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()};
    if (sysctl(mib, 4, &info, &size, NULL, 0) != 0) return false;
    return (info.kp_proc.p_flag & P_TRACED) != 0;
}

// ---- CPU and memory

static int64_t time_value_ns(time_value_t value) {
    return (int64_t)value.seconds * 1000000000LL + (int64_t)value.microseconds * 1000LL;
}

int64_t hopi_perf_process_cpu_ns(void) {
    struct mach_task_basic_info basic;
    mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, (task_info_t)&basic, &count) != KERN_SUCCESS) return -1;
    task_thread_times_info_data_t live;
    count = TASK_THREAD_TIMES_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_THREAD_TIMES_INFO, (task_info_t)&live, &count) != KERN_SUCCESS) return -1;
    return time_value_ns(basic.user_time) + time_value_ns(basic.system_time) + time_value_ns(live.user_time) +
           time_value_ns(live.system_time);
}

int64_t hopi_perf_footprint(void) {
    task_vm_info_data_t info;
    mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) != KERN_SUCCESS) return -1;
    return (int64_t)info.phys_footprint;
}

int64_t hopi_perf_available_memory(void) {
    // iOS 13 and later; below it, no figure (0, as where the OS gives no limit).
    if (@available(iOS 13.0, *)) return (int64_t)os_proc_available_memory();
    return 0;
}

int32_t hopi_perf_threads(hopi_perf_thread_info *out, int32_t max) {
    thread_act_array_t threads;
    mach_msg_type_number_t count = 0;
    if (task_threads(mach_task_self(), &threads, &count) != KERN_SUCCESS) return -1;
    // pthread_mach_thread_np adds no port reference; the main thread has no pthread name of its own.
    thread_t main_thread = pthread_mach_thread_np(pthread_main_thread_np());
    int32_t written = 0;
    for (mach_msg_type_number_t i = 0; i < count; i++) {
        if (written < max) {
            thread_basic_info_data_t basic;
            mach_msg_type_number_t basic_count = THREAD_BASIC_INFO_COUNT;
            if (thread_info(threads[i], THREAD_BASIC_INFO, (thread_info_t)&basic, &basic_count) == KERN_SUCCESS) {
                hopi_perf_thread_info *entry = &out[written++];
                memset(entry, 0, sizeof(*entry));
                entry->user_us = (int64_t)basic.user_time.seconds * 1000000LL + basic.user_time.microseconds;
                entry->system_us = (int64_t)basic.system_time.seconds * 1000000LL + basic.system_time.microseconds;
                entry->cpu_usage = basic.cpu_usage;
                entry->idle = (basic.flags & TH_FLAGS_IDLE) != 0;
                thread_identifier_info_data_t identifier;
                mach_msg_type_number_t identifier_count = THREAD_IDENTIFIER_INFO_COUNT;
                if (thread_info(threads[i], THREAD_IDENTIFIER_INFO, (thread_info_t)&identifier, &identifier_count) == KERN_SUCCESS) {
                    entry->tid = identifier.thread_id;
                }
                thread_extended_info_data_t extended;
                mach_msg_type_number_t extended_count = THREAD_EXTENDED_INFO_COUNT;
                if (thread_info(threads[i], THREAD_EXTENDED_INFO, (thread_info_t)&extended, &extended_count) == KERN_SUCCESS) {
                    strlcpy(entry->name, extended.pth_name, sizeof(entry->name));
                }
                if (entry->name[0] == '\0' && threads[i] == main_thread) strlcpy(entry->name, "main", sizeof(entry->name));
            }
        }
        // Every port task_threads handed out, whether read or not: the classic leak.
        mach_port_deallocate(mach_task_self(), threads[i]);
    }
    vm_deallocate(mach_task_self(), (vm_address_t)threads, sizeof(thread_t) * count);
    return written;
}

int32_t hopi_perf_port_count(void) {
    mach_port_name_array_t names;
    mach_msg_type_number_t name_count = 0;
    mach_port_type_array_t types;
    mach_msg_type_number_t type_count = 0;
    if (mach_port_names(mach_task_self(), &names, &name_count, &types, &type_count) != KERN_SUCCESS) return -1;
    vm_deallocate(mach_task_self(), (vm_address_t)names, sizeof(mach_port_name_t) * name_count);
    vm_deallocate(mach_task_self(), (vm_address_t)types, sizeof(mach_port_type_t) * type_count);
    return (int32_t)name_count;
}

// ---- memory events

static id g_warning_observer;
static dispatch_source_t g_pressure;

void hopi_perf_memory_events_start(hopi_perf_memory_callback callback) {
    hopi_perf_memory_events_stop();
    g_warning_observer = [[NSNotificationCenter defaultCenter] addObserverForName:UIApplicationDidReceiveMemoryWarningNotification
                                                                           object:nil
                                                                            queue:nil
                                                                       usingBlock:^(NSNotification *note) {
                                                                         (void)note;
                                                                         callback(HOPI_PERF_MEMORY_WARNING);
                                                                       }];
    dispatch_source_t source = dispatch_source_create(DISPATCH_SOURCE_TYPE_MEMORYPRESSURE, 0,
                                                      DISPATCH_MEMORYPRESSURE_WARN | DISPATCH_MEMORYPRESSURE_CRITICAL,
                                                      dispatch_get_global_queue(QOS_CLASS_UTILITY, 0));
    if (source != NULL) {
        dispatch_source_set_event_handler(source, ^{
          unsigned long level = dispatch_source_get_data(source);
          if (level & DISPATCH_MEMORYPRESSURE_CRITICAL) {
              callback(HOPI_PERF_MEMORY_PRESSURE_CRITICAL);
          } else if (level & DISPATCH_MEMORYPRESSURE_WARN) {
              callback(HOPI_PERF_MEMORY_PRESSURE_WARN);
          }
        });
        dispatch_resume(source);
        g_pressure = source;
    }
}

void hopi_perf_memory_events_stop(void) {
    if (g_warning_observer != nil) {
        [[NSNotificationCenter defaultCenter] removeObserver:g_warning_observer];
        g_warning_observer = nil;
    }
    if (g_pressure != NULL) {
        dispatch_source_cancel(g_pressure);
        g_pressure = NULL;
    }
}

// ---- frames, tier 2

@interface HopiPerfCadence : NSObject
@property(nonatomic) hopi_perf_vsync_callback callback;
@property(nonatomic) CFTimeInterval last;
@property(nonatomic, strong) CADisplayLink *link;
@property(nonatomic, strong) id background;
@property(nonatomic, strong) id foreground;
@end

@implementation HopiPerfCadence
- (void)tick:(CADisplayLink *)link {
    CFTimeInterval timestamp = link.timestamp;
    CFTimeInterval period = link.targetTimestamp - link.timestamp;
    CFTimeInterval last = self.last;
    self.last = timestamp;
    if (last <= 0 || period <= 0) return;
    CFTimeInterval interval = timestamp - last;
    int32_t missed = interval > 1.5 * period ? (int32_t)llround(interval / period) - 1 : 0;
    self.callback(hopi_perf_now_ns(), (int64_t)(interval * 1e9), (int64_t)(period * 1e9), missed);
}
@end

// Main queue only.
static HopiPerfCadence *g_cadence;
static _Atomic bool g_cadence_running;

void hopi_perf_cadence_start(hopi_perf_vsync_callback callback) {
    atomic_store(&g_cadence_running, true);
    dispatch_async(dispatch_get_main_queue(), ^{
      if (g_cadence != nil || !atomic_load(&g_cadence_running)) return;
      HopiPerfCadence *cadence = [HopiPerfCadence new];
      cadence.callback = callback;
      // The display link retains its target; invalidate releases it.
      cadence.link = [CADisplayLink displayLinkWithTarget:cadence selector:@selector(tick:)];
      [cadence.link addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
      __weak HopiPerfCadence *weak = cadence;
      NSNotificationCenter *center = [NSNotificationCenter defaultCenter];
      cadence.background = [center addObserverForName:UIApplicationDidEnterBackgroundNotification
                                                object:nil
                                                 queue:[NSOperationQueue mainQueue]
                                            usingBlock:^(NSNotification *note) {
                                              (void)note;
                                              weak.link.paused = YES;
                                              weak.last = 0;
                                            }];
      cadence.foreground = [center addObserverForName:UIApplicationWillEnterForegroundNotification
                                                object:nil
                                                 queue:[NSOperationQueue mainQueue]
                                            usingBlock:^(NSNotification *note) {
                                              (void)note;
                                              weak.link.paused = NO;
                                            }];
      g_cadence = cadence;
    });
}

void hopi_perf_cadence_stop(void) {
    atomic_store(&g_cadence_running, false);
    dispatch_async(dispatch_get_main_queue(), ^{
      HopiPerfCadence *cadence = g_cadence;
      if (cadence == nil || atomic_load(&g_cadence_running)) return;
      g_cadence = nil;
      [cadence.link invalidate];
      cadence.link = nil;
      [[NSNotificationCenter defaultCenter] removeObserver:cadence.background];
      [[NSNotificationCenter defaultCenter] removeObserver:cadence.foreground];
    });
}

bool hopi_perf_cadence_running(void) {
    return atomic_load(&g_cadence_running);
}

// ---- the main screen

int32_t hopi_perf_max_fps_main_thread(void) {
    NSInteger fps = UIScreen.mainScreen.maximumFramesPerSecond;
    return fps > 0 ? (int32_t)fps : 60;
}

bool hopi_perf_high_rate_allowed(void) {
    id value = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CADisableMinimumFrameDurationOnPhone"];
    return [value respondsToSelector:@selector(boolValue)] && [value boolValue];
}
