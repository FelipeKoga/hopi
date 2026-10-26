// Automatic URLSession capture, observe only (docs/design/urlsession-capture.md).
//
// Hooks, installed once by hopi_us_start:
//   1. -[NSURLSessionTask resume] on every class of the task class chains that defines it:
//      creates the per-task state on the first resume of a task.
//   2. -[__NSCFURLLocalSessionConnection _didReceiveData:]: retains each new chunk (no copy)
//      while the kept bytes are below the cap, counts it past the cap.
//   3. -[__NSCFURLLocalSessionConnection _didFinishWithError:]: reads the response and ends the record.
//   4. NSURLSession's upload factories: remembers the body data (or that it is a file or a stream).
//   5. Metadata path only (background sessions, or an OS that fails the self-check): KVO on the
//      task's state as the terminal signal.
// Every replaced implementation calls the previous one with its own selector, first for 2 to 4
// (the app's call happens unchanged, then Hopi observes), before for 1 (the state must exist
// before the first byte can arrive). Nothing is re-sent, no method is added, no app or SDK class
// is touched, and the Kotlin callbacks run on the capture queue only.
//
// Rules (docs/design/rules.md, "URLSession"), installed by hopi_us_rules_enable and idle while no
// rule set is published (one atomic load, gHasRules, is every hook's no-rule path):
//   6. +[NSURLSession sessionWithConfiguration:delegate:delegateQueue:]: passes a copy of the
//      configuration with HopiUSRuleProtocol first in protocolClasses (background ones untouched).
//      URLSession.shared gets the protocol by +[NSURLProtocol registerClass:] while rules exist.
//   7. HopiUSRuleProtocol claims, in +canInitWithTask:, only a task whose first matching rule is a
//      mock or fail, and answers it itself: no network, no challenge, no TLS. The byte path above
//      records the task as usual.
//   8. Task factories (data and download, request and URL, public and private) and -suspend,
//      installed the first time an edit or delay rule is published: an edit rule's request is
//      edited before the original factory runs, so the app's own session sends it; a delay is
//      planned on the task.
//   9. Hook 1 defers the first resume of a task with a planned delay (dispatch_after).
// The upload factories of hook 4 apply edits too. Nothing here weakens the app's TLS handling: a
// real request is always sent by the app's own session, with its delegate, pinning and proxy.

#import "hopi_urlsession.h"

#import <objc/message.h>
#import <objc/runtime.h>
#import <os/lock.h>
#import <pthread.h>
#import <stdatomic.h>
#import <time.h>

enum { HOPI_US_PATH_BYTES = 0, HOPI_US_PATH_METADATA = 1 };

#define HOPI_US_RING_SIZE 4

// Expected type encodings of the private selectors; anything else fails the self-check.
static const char *const kDidReceiveDataEncoding = "v24@0:8@16";
static const char *const kDidFinishEncoding = "v24@0:8@16";
static const char *const kPrivateUploadEncoding = "@48@0:8@16@24@32@?40";

static dispatch_queue_t gQueue;
static hopi_us_callback gOnStarted;
static hopi_us_callback gOnFinished;
static int64_t gMaxBodyBytes;
static Ivar gTaskIvar;
static bool gBytePath;
static os_unfair_lock gAttachLock = OS_UNFAIR_LOCK_INIT;
static _Atomic bool gForceMetadata;
static _Atomic int64_t gInstallCount;
static _Atomic int64_t gCountMismatches;
static _Atomic int64_t gReplaysIgnored;
static _Atomic int64_t gRetainedHighWater;
// Test-only timing of Hopi's own part of each hook (off: one relaxed load per call).
static _Atomic bool gTiming;
static _Atomic int64_t gHookNs[3];
static _Atomic int64_t gHookCalls[3];
static _Atomic int64_t gMainThreadConnectionCalls;

#define HOPI_US_TIMED(index, call)                                                     \
    do {                                                                               \
        if (atomic_load_explicit(&gTiming, memory_order_relaxed)) {                    \
            int64_t hopiStart = hopi_us_now_ns();                                      \
            call;                                                                      \
            atomic_fetch_add(&gHookNs[index], hopi_us_now_ns() - hopiStart);           \
            atomic_fetch_add(&gHookCalls[index], 1);                                   \
            if ((index) > 0 && pthread_main_np()) atomic_fetch_add(&gMainThreadConnectionCalls, 1); \
        } else {                                                                       \
            call;                                                                      \
        }                                                                              \
    } while (0)
static char kStateKey;
static char kUploadBodyKey;
static char kObserverContext;

// Rules. All of it is idle until a rule set is published.
static _Atomic bool gHasRules;            // a rule set is published: the one load of every no-rule path
// Live factory plans: hook 1 and -suspend look at a task's marks only while this is non-zero.
static _Atomic int64_t gTaskMarks;
static _Atomic int64_t gCanInitCalls;
static hopi_us_exhausted_callback gOnExhausted;
static char kPlanKey;
static char kDecisionKey;

static int64_t hopi_us_now_ns(void) { return (int64_t)clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }

static int64_t hopi_us_wall_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_REALTIME, &now);
    return (int64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static void hopi_us_raise_high_water(int64_t value) {
    int64_t current = atomic_load(&gRetainedHighWater);
    while (value > current && !atomic_compare_exchange_weak(&gRetainedHighWater, &current, value)) {
    }
}

/** Mirrors core's responseSkipReason: these content types keep no bytes. */
static bool hopi_us_skips_mime(NSString *mime) {
    if (mime == nil) return false;
    NSString *type = [[mime componentsSeparatedByString:@";"].firstObject stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet].lowercaseString;
    if ([type isEqualToString:@"text/event-stream"]) return true;
    if ([type isEqualToString:@"image/svg+xml"]) return false;
    if ([type hasPrefix:@"image/"] || [type hasPrefix:@"video/"] || [type hasPrefix:@"audio/"] || [type hasPrefix:@"font/"] ||
        [type hasPrefix:@"application/grpc+"]) {
        return true;
    }
    static NSSet<NSString *> *binary;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        binary = [NSSet setWithArray:@[
            @"application/octet-stream", @"application/zip", @"application/gzip", @"application/x-gzip",
            @"application/x-bzip2", @"application/x-tar", @"application/x-7z-compressed",
            @"application/x-rar-compressed", @"application/vnd.rar", @"application/zstd", @"application/pdf",
            @"application/wasm", @"application/protobuf", @"application/x-protobuf",
            @"application/vnd.google.protobuf", @"application/grpc", @"application/msgpack",
            @"application/x-msgpack", @"application/cbor", @"application/vnd.android.package-archive",
        ]];
    });
    return [binary containsObject:type];
}

#pragma mark - Per-task state

@interface HopiUSTask : NSObject {
@public
    os_unfair_lock _lock;
    int32_t _path;
    int64_t _storeId;
    int64_t _startedAtMs;
    int64_t _startNs;
    int64_t _endNs;
    NSURLRequest *_request;
    NSData *_uploadBody;
    int64_t _uploadLength;
    int32_t _requestBodyKind;
    bool _captureBody;
    bool _decided;
    int32_t _responseKind;
    NSMutableArray<NSData *> *_chunks;
    // Chunks counted past the cap, by identity only: weak, so the dedupe keeps nothing alive,
    // and a freed chunk reads nil, so a new chunk at its address is never taken for a replay.
    __weak NSData *_recent[HOPI_US_RING_SIZE];
    NSUInteger _recentNext;
    int64_t _keptBytes;
    int64_t _totalBytes;
    _Atomic bool _finished;
    NSHTTPURLResponse *_response;
    NSError *_error;
    NSURL *_currentURL;
    int64_t _bytesSent;
    NSString *_protocol;
    // The rule applied (0: none) and its mark, and whether the rule protocol answered the task.
    int64_t _ruleId;
    NSString *_ruleApplied;
    bool _byProtocol;
}
@end

@implementation HopiUSTask
@end

/** Hook 4's record of a request body: at most the cap of its bytes, and its whole length. */
@interface HopiUSBody : NSObject {
@public
    NSData *_bytes;
    int64_t _length;
}
@end

@implementation HopiUSBody
@end

static HopiUSTask *hopi_us_state(NSURLSessionTask *task) { return objc_getAssociatedObject(task, &kStateKey); }

static NSURLSessionTask *hopi_us_connection_task(id connection) {
    id task = object_getIvar(connection, gTaskIvar);
    return [task isKindOfClass:NSURLSessionTask.class] ? task : nil;
}

#pragma mark - Rules: model and matcher

/** A rule's hit counter: the one count every source claims through (Kotlin's RuleHits on iOS). */
@interface HopiUSHits : NSObject {
@public
    _Atomic int64_t _count;
}
@end

@implementation HopiUSHits
@end

/** One compare-and-increment against times (0: unlimited), as Kotlin's RuleHits.claim. */
static int32_t hopi_us_claim_hits(HopiUSHits *hits, int64_t times) {
    if (times <= 0) {
        atomic_fetch_add(&hits->_count, 1);
        return HOPI_US_CLAIM_CLAIMED;
    }
    int64_t current = atomic_load(&hits->_count);
    while (true) {
        if (current >= times) return HOPI_US_CLAIM_REFUSED;
        if (atomic_compare_exchange_weak(&hits->_count, &current, current + 1)) {
            return current + 1 == times ? HOPI_US_CLAIM_LAST : HOPI_US_CLAIM_CLAIMED;
        }
    }
}

enum { HOPI_US_GLOB_ANY = -1, HOPI_US_GLOB_SEGMENT = -2, HOPI_US_GLOB_MAX = 256 };

/**
 * Kotlin's Glob, the second implementation (the shared vectors keep the two equal): `*` matches any
 * run of characters, within one segment (no `/`) for a path, where `**` crosses segments; adjacent
 * wildcards merge. Matched as a state-set simulation over UTF-16 units, never backtracking: at most
 * O(pattern × input), the pattern capped at 256 characters by the rule validation.
 */
@interface HopiUSGlob : NSObject {
@public
    NSUInteger _count;
    bool _never;
    int32_t _tokens[HOPI_US_GLOB_MAX];
}
@end

@implementation HopiUSGlob
@end

static HopiUSGlob *hopi_us_glob(NSString *pattern, bool path) {
    if (pattern == nil) return nil;
    HopiUSGlob *glob = [HopiUSGlob new];
    NSUInteger length = pattern.length;
    if (length > HOPI_US_GLOB_MAX) {
        glob->_never = true;  // rejected by the validation; never reached
        return glob;
    }
    NSUInteger n = 0;
    NSUInteger i = 0;
    while (i < length) {
        unichar c = [pattern characterAtIndex:i];
        if (c == '*') {
            NSUInteger stars = 1;
            while (i + stars < length && [pattern characterAtIndex:i + stars] == '*') stars++;
            int32_t token = path && stars == 1 ? HOPI_US_GLOB_SEGMENT : HOPI_US_GLOB_ANY;
            if (n > 0 && glob->_tokens[n - 1] < 0) {
                if (token == HOPI_US_GLOB_ANY) glob->_tokens[n - 1] = HOPI_US_GLOB_ANY;
            } else {
                glob->_tokens[n++] = token;
            }
            i += stars;
        } else {
            glob->_tokens[n++] = c;
            i++;
        }
    }
    glob->_count = n;
    return glob;
}

/** A wildcard's state also holds the state after it (it matched nothing so far). */
static void hopi_us_glob_close(const int32_t *tokens, NSUInteger count, bool *states) {
    for (NSUInteger j = 0; j < count; j++) {
        if (states[j] && tokens[j] < 0) states[j + 1] = true;
    }
}

static bool hopi_us_glob_matches(HopiUSGlob *glob, NSString *input) {
    if (glob->_never || input == nil) return false;
    NSUInteger count = glob->_count;
    const int32_t *tokens = glob->_tokens;
    bool first[HOPI_US_GLOB_MAX + 1];
    bool second[HOPI_US_GLOB_MAX + 1];
    bool *states = first;
    bool *next = second;
    memset(states, 0, count + 1);
    states[0] = true;
    hopi_us_glob_close(tokens, count, states);
    CFStringRef text = (__bridge CFStringRef)input;
    CFIndex length = CFStringGetLength(text);
    CFStringInlineBuffer buffer;
    CFStringInitInlineBuffer(text, &buffer, CFRangeMake(0, length));
    for (CFIndex i = 0; i < length; i++) {
        UniChar c = CFStringGetCharacterFromInlineBuffer(&buffer, i);
        memset(next, 0, count + 1);
        bool live = false;
        for (NSUInteger j = 0; j < count; j++) {
            if (!states[j]) continue;
            int32_t token = tokens[j];
            if (token == HOPI_US_GLOB_ANY || (token == HOPI_US_GLOB_SEGMENT && c != '/')) {
                next[j] = true;
                live = true;
            } else if (token >= 0 && (UniChar)token == c) {
                next[j + 1] = true;
                live = true;
            }
        }
        if (!live) return false;
        hopi_us_glob_close(tokens, count, next);
        bool *swap = states;
        states = next;
        next = swap;
    }
    return states[count];
}

static int hopi_us_hex(uint8_t c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/** Kotlin's UrlParts.percentDecode: `%XX` as UTF-8 and `+` as a space; a malformed escape stays as written. */
static NSString *hopi_us_percent_decode(NSString *text) {
    static NSCharacterSet *special;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        special = [NSCharacterSet characterSetWithCharactersInString:@"%+"];
    });
    if ([text rangeOfCharacterFromSet:special].location == NSNotFound) return text;
    NSData *utf8 = [text dataUsingEncoding:NSUTF8StringEncoding];
    const uint8_t *src = utf8.bytes;
    NSUInteger length = utf8.length;
    NSMutableData *out = [NSMutableData dataWithLength:length];
    uint8_t *dst = out.mutableBytes;
    NSUInteger count = 0;
    for (NSUInteger i = 0; i < length;) {
        uint8_t b = src[i];
        if (b == '%' && i + 2 < length && hopi_us_hex(src[i + 1]) >= 0 && hopi_us_hex(src[i + 2]) >= 0) {
            dst[count++] = (uint8_t)((hopi_us_hex(src[i + 1]) << 4) | hopi_us_hex(src[i + 2]));
            i += 3;
        } else {
            dst[count++] = b == '+' ? ' ' : b;
            i++;
        }
    }
    return [[NSString alloc] initWithBytes:dst length:count encoding:NSUTF8StringEncoding] ?: text;
}

/** A query or header predicate. */
@interface HopiUSPredicate : NSObject {
@public
    NSString *_name;
    HopiUSGlob *_value;  // nil: present
    bool _absent;
}
@end

@implementation HopiUSPredicate
@end

/** One URLSession rule, as the Kotlin side compiled it (hopi_us_rules_*); immutable once published. */
@interface HopiUSRule : NSObject {
@public
    int64_t _id;
    HopiUSHits *_hits;
    int64_t _times;
    NSString *_applied;
    int32_t _action;
    // Match; nil and -1 match anything.
    NSSet<NSString *> *_methods;
    NSString *_scheme;
    int32_t _port;
    HopiUSGlob *_host;
    HopiUSGlob *_path;
    NSMutableArray<HopiUSPredicate *> *_query;
    NSMutableArray<HopiUSPredicate *> *_headers;
    // Delay, mock, fail.
    int64_t _delayRequestMs;
    int64_t _delayResponseMs;
    int32_t _status;
    NSDictionary<NSString *, NSString *> *_mockHeaders;
    NSData *_body;
    int32_t _failKind;
    // Request edit.
    NSString *_editMethod;
    NSString *_editHost;
    int32_t _editPort;
    NSString *_editPath;
    NSSet<NSString *> *_dropQuery;
    NSArray<NSString *> *_addQuery;
    NSSet<NSString *> *_removeHeaders;
    NSSet<NSString *> *_keepHeaders;
    NSSet<NSString *> *_stripHeaders;
    NSArray<NSString *> *_addHeaders;
    NSData *_editBody;
}
@end

@implementation HopiUSRule
@end

/** The published rules in the order they are tried (also the builder, before it is published). */
@interface HopiUSRuleSet : NSObject {
@public
    NSMutableArray<HopiUSRule *> *_rules;
    // An edit or delay-only rule is in the set: the task factories plan.
    bool _plans;
}
@end

@implementation HopiUSRuleSet
@end

// The published set, retained, or NULL; swapped under gRulesLock, which a reader holds only to
// retain it. gHasRules mirrors it for the fast path.
static os_unfair_lock gRulesLock = OS_UNFAIR_LOCK_INIT;
static void *gRuleSet;

/** The published set, retained by the caller's reference, or nil: one atomic load when there is none. */
static HopiUSRuleSet *hopi_us_rules(void) {
    if (!atomic_load_explicit(&gHasRules, memory_order_acquire)) return nil;
    HopiUSRuleSet *set = nil;
    os_unfair_lock_lock(&gRulesLock);
    if (gRuleSet != NULL) set = CFBridgingRelease(CFRetain(gRuleSet));
    os_unfair_lock_unlock(&gRulesLock);
    return set;
}

/** A request as rules see it (Kotlin's RuleRequest): method, scheme, host and port read at once, path and query on demand. */
@interface HopiUSView : NSObject {
@public
    NSURLRequest *_request;
    NSURL *_url;
    NSString *_method;
    NSString *_scheme;
    NSString *_host;  // lowercased
    NSInteger _port;
    NSString *_path;
    NSArray<NSString *> *_query;  // decoded [name, value, …]
    bool _parsed;
}
@end

@implementation HopiUSView
@end

/** Nil for anything but an absolute http or https URL with a host: rules never apply to it. */
static HopiUSView *hopi_us_view(NSURLRequest *request) {
    if (![request isKindOfClass:NSURLRequest.class]) return nil;
    NSURL *url = request.URL.absoluteURL;
    NSString *scheme = url.scheme.lowercaseString;
    bool https = [scheme isEqualToString:@"https"];
    if (!https && ![scheme isEqualToString:@"http"]) return nil;
    NSString *host = url.host;
    if (host.length == 0) return nil;
    HopiUSView *view = [HopiUSView new];
    view->_request = request;
    view->_url = url;
    view->_method = request.HTTPMethod ?: @"GET";
    view->_scheme = scheme;
    view->_host = host.lowercaseString;
    NSNumber *port = url.port;
    view->_port = port != nil ? port.integerValue : (https ? 443 : 80);
    return view;
}

static void hopi_us_view_parse(HopiUSView *view) {
    if (view->_parsed) return;
    view->_parsed = true;
    NSURLComponents *components = [NSURLComponents componentsWithURL:view->_url resolvingAgainstBaseURL:NO];
    NSString *path = components.percentEncodedPath;
    view->_path = path.length > 0 ? path : @"/";
    NSMutableArray<NSString *> *pairs = [NSMutableArray array];
    for (NSString *part in [components.percentEncodedQuery componentsSeparatedByString:@"&"]) {
        if (part.length == 0) continue;
        NSRange equals = [part rangeOfString:@"="];
        if (equals.location == NSNotFound) {
            [pairs addObject:hopi_us_percent_decode(part)];
            [pairs addObject:@""];
        } else {
            [pairs addObject:hopi_us_percent_decode([part substringToIndex:equals.location])];
            [pairs addObject:hopi_us_percent_decode([part substringFromIndex:equals.location + 1])];
        }
    }
    view->_query = pairs;
}

static bool hopi_us_predicate_holds(HopiUSPredicate *predicate, NSArray<NSString *> *values) {
    if (predicate->_absent) return values.count == 0;
    if (predicate->_value == nil) return values.count > 0;
    for (NSString *value in values) {
        if (hopi_us_glob_matches(predicate->_value, value)) return true;
    }
    return false;
}

/**
 * A header field's values as Kotlin's sources list them: NSURLRequest keeps one field per name and
 * joins repeated values with ",", so the whole field is tried, then each trimmed element, and a
 * glob holds when it matches any of them (both a value set twice and one value with a comma).
 */
static NSArray<NSString *> *hopi_us_header_values(NSString *field) {
    if (field == nil) return @[];
    if (![field containsString:@","]) return @[ field ];
    NSMutableArray<NSString *> *values = [NSMutableArray arrayWithObject:field];
    for (NSString *part in [field componentsSeparatedByString:@","]) {
        [values addObject:[part stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet]];
    }
    return values;
}

/** Kotlin's CompiledRule.matches, cheapest first; claims nothing. */
static bool hopi_us_rule_matches(HopiUSRule *rule, HopiUSView *view) {
    if (rule->_methods != nil && ![rule->_methods containsObject:view->_method]) return false;
    if (rule->_scheme != nil && ![rule->_scheme isEqualToString:view->_scheme]) return false;
    if (rule->_port >= 0 && rule->_port != view->_port) return false;
    if (rule->_host != nil && !hopi_us_glob_matches(rule->_host, view->_host)) return false;
    if (rule->_path != nil || rule->_query.count > 0) hopi_us_view_parse(view);
    if (rule->_path != nil && !hopi_us_glob_matches(rule->_path, view->_path)) return false;
    for (HopiUSPredicate *predicate in rule->_query) {
        NSMutableArray<NSString *> *values = [NSMutableArray array];
        for (NSUInteger i = 0; i + 1 < view->_query.count; i += 2) {
            if ([view->_query[i] isEqualToString:predicate->_name]) [values addObject:view->_query[i + 1]];
        }
        if (!hopi_us_predicate_holds(predicate, values)) return false;
    }
    for (HopiUSPredicate *predicate in rule->_headers) {
        NSString *value = [view->_request valueForHTTPHeaderField:predicate->_name];
        if (!hopi_us_predicate_holds(predicate, hopi_us_header_values(value))) return false;
    }
    return true;
}

static bool hopi_us_exhausted(HopiUSRule *rule) {
    return rule->_times > 0 && atomic_load(&rule->_hits->_count) >= rule->_times;
}

/**
 * Claims one application of rule: false when it is exhausted (it does not match, the scan goes
 * on). The claim that uses up its times has the Kotlin side republish without it, on the capture
 * queue: these hooks never call Kotlin.
 */
static bool hopi_us_claim(HopiUSRule *rule) {
    int32_t result = hopi_us_claim_hits(rule->_hits, rule->_times);
    if (result == HOPI_US_CLAIM_REFUSED) return false;
    hopi_us_exhausted_callback callback = gOnExhausted;
    if (result == HOPI_US_CLAIM_LAST && callback != NULL) {
        dispatch_async(gQueue, ^{
            callback();
        });
    }
    return true;
}

enum { HOPI_US_RESUME_IDLE = 0, HOPI_US_RESUME_PENDING = 1, HOPI_US_RESUME_DONE = 2 };

/**
 * A task factory's plan for its task: the edit or delay rule applied at creation. Counted in
 * gTaskMarks while alive (an associated object of the task, so it goes with the task).
 */
@interface HopiUSPlan : NSObject {
@public
    HopiUSRule *_rule;
    int32_t _resume;  // the deferred first resume, guarded by gPlanLock
    uint64_t _generation;
}
@end

@implementation HopiUSPlan
- (instancetype)init {
    self = [super init];
    if (self != nil) atomic_fetch_add(&gTaskMarks, 1);
    return self;
}

- (void)dealloc {
    atomic_fetch_sub(&gTaskMarks, 1);
}
@end

static os_unfair_lock gPlanLock = OS_UNFAIR_LOCK_INIT;

#pragma mark - Finishing

@interface HopiUSObserver : NSObject
@end

static HopiUSObserver *gObserver;

/**
 * The error as the app will see it: CFNetwork hands the connection a kCFErrorDomainCFNetwork error
 * whose codes are the NSURLError codes (CFNetworkErrors.h), which the task reports in
 * NSURLErrorDomain afterwards.
 */
static NSError *hopi_us_app_error(NSError *error) {
    if (error == nil || ![error.domain isEqualToString:(__bridge NSString *)kCFErrorDomainCFNetwork]) return error;
    return [NSError errorWithDomain:NSURLErrorDomain code:error.code userInfo:error.userInfo];
}

static NSString *hopi_us_error_name(NSInteger code) {
    switch (code) {
        case NSURLErrorCancelled: return @"cancelled";
        case NSURLErrorTimedOut: return @"timed out";
        case NSURLErrorCannotFindHost: return @"cannot find host";
        case NSURLErrorCannotConnectToHost: return @"cannot connect to host";
        case NSURLErrorNetworkConnectionLost: return @"network connection lost";
        case NSURLErrorNotConnectedToInternet: return @"not connected to the internet";
        case NSURLErrorDNSLookupFailed: return @"DNS lookup failed";
        case NSURLErrorBadServerResponse: return @"bad server response";
        case NSURLErrorCannotParseResponse: return @"cannot parse response";
        case NSURLErrorSecureConnectionFailed: return @"secure connection failed";
        case NSURLErrorServerCertificateUntrusted: return @"server certificate untrusted";
        case NSURLErrorAppTransportSecurityRequiresSecureConnection: return @"App Transport Security requires a secure connection";
        default: return nil;
    }
}

static NSString *hopi_us_protocol_of(NSURLSessionTask *task) {
    SEL selector = NSSelectorFromString(@"_incompleteTaskMetrics");
    if (![task respondsToSelector:selector]) return nil;
    @try {
        id metrics = [task valueForKey:@"_incompleteTaskMetrics"];
        if (![metrics isKindOfClass:NSURLSessionTaskMetrics.class]) return nil;
        NSString *name = ((NSURLSessionTaskMetrics *)metrics).transactionMetrics.lastObject.networkProtocolName;
        return [name isKindOfClass:NSString.class] ? name : nil;
    } @catch (NSException *exception) {
        return nil;
    }
}

/** Ends the record once: reads what the task holds now and posts it to the capture queue. */
static void hopi_us_finish(NSURLSessionTask *task, HopiUSTask *state, NSError *error) {
    bool expected = false;
    if (!atomic_compare_exchange_strong(&state->_finished, &expected, true)) return;
    int64_t end = hopi_us_now_ns();
    NSURLResponse *response = task.response;
    int64_t received = task.countOfBytesReceived;
    int64_t sent = task.countOfBytesSent;
    NSURL *current = task.currentRequest.URL;
    NSString *protocol = state->_path == HOPI_US_PATH_BYTES ? hopi_us_protocol_of(task) : nil;
    os_unfair_lock_lock(&state->_lock);
    state->_endNs = end;
    state->_response = [response isKindOfClass:NSHTTPURLResponse.class] ? (NSHTTPURLResponse *)response : nil;
    state->_error = hopi_us_app_error(error);
    state->_currentURL = current;
    state->_bytesSent = sent;
    state->_protocol = protocol;
    if (state->_path == HOPI_US_PATH_METADATA) {
        // KVO reports the completed state after CFNetwork updated the public counters.
        state->_totalBytes = received;
    } else if (received > state->_totalBytes) {
        // Not a cut: countOfBytesReceived is often still 0 or partial here (uploads, redirects,
        // downloads), so it is never used to shorten the record; a test-only counter shows a
        // count that fell short of it.
        atomic_fetch_add(&gCountMismatches, 1);
    }
    os_unfair_lock_unlock(&state->_lock);
    dispatch_async(gQueue, ^{
        gOnFinished((__bridge_retained void *)state);
    });
}

/** Metadata path: stops observing a completed task and ends its record (both at most once). */
static void hopi_us_end_observed(NSURLSessionTask *task, HopiUSTask *state) {
    @try {
        [task removeObserver:gObserver forKeyPath:@"state" context:&kObserverContext];
    } @catch (NSException *exception) {
        // Already removed by a concurrent completion notification.
    }
    hopi_us_finish(task, state, task.error);
}

@implementation HopiUSObserver
- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if (context != &kObserverContext) {
        [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
        return;
    }
    NSURLSessionTask *task = object;
    if (task.state != NSURLSessionTaskStateCompleted) return;
    HopiUSTask *state = hopi_us_state(task);
    if (state == nil || atomic_load(&state->_finished)) return;
    hopi_us_end_observed(task, state);
}
@end

#pragma mark - Hook bodies

static bool hopi_us_records(NSURLSessionTask *task) {
    // HTTP only: a data:, file: or custom-scheme load ends with a plain NSURLResponse, not a record.
    NSString *scheme = task.originalRequest.URL.scheme;
    if (scheme == nil ||
        ([scheme caseInsensitiveCompare:@"http"] != NSOrderedSame && [scheme caseInsensitiveCompare:@"https"] != NSOrderedSame)) {
        return false;
    }
    static Class webSocket, stream, aggregate;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        webSocket = NSClassFromString(@"NSURLSessionWebSocketTask");
        stream = NSClassFromString(@"NSURLSessionStreamTask");
        aggregate = NSClassFromString(@"AVAggregateAssetDownloadTask");
    });
    if (webSocket != nil && [task isKindOfClass:webSocket]) return false;
    if (stream != nil && [task isKindOfClass:stream]) return false;
    if (aggregate != nil && [task isKindOfClass:aggregate]) return false;
    return true;
}

static bool hopi_us_is_background(NSURLSessionTask *task) {
    static Class background;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        background = NSClassFromString(@"__NSCFBackgroundSessionTask");
    });
    return background != nil && [task isKindOfClass:background];
}

/** Hook 1, before the original resume: the state must exist before the first byte arrives. */
static void hopi_us_on_resume(NSURLSessionTask *task) {
    if (task.state != NSURLSessionTaskStateSuspended) return;  // resumed before start, or cancelled
    if (hopi_us_state(task) != nil) return;                     // recorded already, resumed again
    // Resumed before start() and suspended since: recording it now would show part of the
    // transfer as all of it.
    if (task.response != nil || task.countOfBytesReceived > 0 || task.countOfBytesSent > 0) return;
    if (!hopi_us_records(task)) return;

    // Built whole before anyone can see it: a concurrent resume of the same task finds either
    // nothing (and waits on the lock below) or a complete state.
    HopiUSTask *state = [HopiUSTask new];
    state->_lock = OS_UNFAIR_LOCK_INIT;
    bool metadata = !gBytePath || atomic_load(&gForceMetadata) || hopi_us_is_background(task);
    state->_path = metadata ? HOPI_US_PATH_METADATA : HOPI_US_PATH_BYTES;
    state->_startedAtMs = hopi_us_wall_ms();
    state->_startNs = hopi_us_now_ns();
    state->_request = task.originalRequest;
    bool download = [task isKindOfClass:NSURLSessionDownloadTask.class];
    state->_captureBody = !metadata && !download;
    state->_responseKind = metadata ? HOPI_US_RESPONSE_NOT_OBSERVABLE : (download ? HOPI_US_RESPONSE_DOWNLOAD : HOPI_US_RESPONSE_CAPTURED);
    state->_chunks = [NSMutableArray array];

    id marker = objc_getAssociatedObject(task, &kUploadBodyKey);
    if ([marker isKindOfClass:HopiUSBody.class]) {
        HopiUSBody *body = marker;
        state->_uploadBody = body->_bytes;
        state->_uploadLength = body->_length;
        state->_requestBodyKind = HOPI_US_BODY_DATA;
    } else if ([marker isKindOfClass:NSNumber.class]) {
        state->_requestBodyKind = [marker intValue];
    } else if (state->_request.HTTPBodyStream != nil) {
        state->_requestBodyKind = HOPI_US_BODY_STREAM;
    } else if (state->_request.HTTPBody != nil) {
        state->_uploadBody = state->_request.HTTPBody;
        state->_uploadLength = (int64_t)state->_uploadBody.length;
        state->_requestBodyKind = HOPI_US_BODY_DATA;
    } else if ([task isKindOfClass:NSURLSessionUploadTask.class]) {
        state->_requestBodyKind = HOPI_US_BODY_NOT_OBSERVED;
    } else {
        state->_requestBodyKind = HOPI_US_BODY_NONE;
    }
    if (atomic_load_explicit(&gTaskMarks, memory_order_relaxed) > 0) {
        // A factory's edit or delay plan marks the record.
        HopiUSPlan *plan = objc_getAssociatedObject(task, &kPlanKey);
        if ([plan isKindOfClass:HopiUSPlan.class]) {
            state->_ruleId = plan->_rule->_id;
            state->_ruleApplied = plan->_rule->_applied;
        }
    }

    os_unfair_lock_lock(&gAttachLock);
    if (hopi_us_state(task) != nil) {
        os_unfair_lock_unlock(&gAttachLock);
        return;
    }
    // Queued before the state is visible, so the start callback always precedes the end one.
    dispatch_async(gQueue, ^{
        gOnStarted((__bridge_retained void *)state);
    });
    objc_setAssociatedObject(task, &kStateKey, state, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    // The state holds the body now; the task no longer needs to.
    if (marker != nil) objc_setAssociatedObject(task, &kUploadBodyKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    os_unfair_lock_unlock(&gAttachLock);

    if (metadata) {
        [task addObserver:gObserver forKeyPath:@"state" options:0 context:&kObserverContext];
        // A concurrent resume can have run the whole task before the observer was in place.
        if (task.state == NSURLSessionTaskStateCompleted && !atomic_load(&state->_finished)) hopi_us_end_observed(task, state);
    }
}

static bool hopi_us_contains(NSArray<NSData *> *array, NSData *data) {
    for (NSData *item in array) {
        if (item == data) return true;
    }
    return false;
}

static bool hopi_us_recent(HopiUSTask *state, NSData *data) {
    for (NSUInteger i = 0; i < HOPI_US_RING_SIZE; i++) {
        if (state->_recent[i] == data) return true;
    }
    return false;
}

/**
 * A copy of the first `length` bytes of the chunk that crosses the cap: at most what is left of
 * the cap, the one bounded copy on CFNetwork's queue. A subrange would share the buffer and so
 * keep the whole chunk alive, past the cap.
 */
static NSData *hopi_us_prefix(NSData *data, NSUInteger length) {
    NSMutableData *copy = [NSMutableData dataWithLength:length];
    __block NSUInteger copied = 0;
    [data enumerateByteRangesUsingBlock:^(const void *bytes, NSRange range, BOOL *stop) {
        NSUInteger take = MIN(range.length, length - copied);
        memcpy((uint8_t *)copy.mutableBytes + copied, bytes, take);
        copied += take;
        if (copied >= length) *stop = YES;
    }];
    return copy;
}

/** Hook 2, after the original: keeps or counts a chunk, ignoring a replay of one already counted. */
static void hopi_us_on_data(id connection, id data) {
    if (![data isKindOfClass:NSData.class]) return;
    NSURLSessionTask *task = hopi_us_connection_task(connection);
    if (task == nil) return;
    HopiUSTask *state = hopi_us_state(task);
    if (state == nil || state->_path != HOPI_US_PATH_BYTES) return;
    NSData *chunk = data;
    os_unfair_lock_lock(&state->_lock);
    if (atomic_load(&state->_finished)) {
        os_unfair_lock_unlock(&state->_lock);
        return;
    }
    if (hopi_us_contains(state->_chunks, chunk) || hopi_us_recent(state, chunk)) {
        os_unfair_lock_unlock(&state->_lock);
        atomic_fetch_add(&gReplaysIgnored, 1);
        return;
    }
    if (!state->_decided) {
        state->_decided = true;
        if (state->_captureBody) {
            NSURLResponse *response = task.response;
            if (hopi_us_skips_mime(response.MIMEType)) {
                state->_captureBody = false;
                state->_responseKind = HOPI_US_RESPONSE_SKIPPED;
            }
        }
    }
    int64_t length = (int64_t)chunk.length;
    state->_totalBytes += length;
    int64_t room = state->_captureBody ? gMaxBodyBytes - state->_keptBytes : 0;
    if (room >= length) {
        [state->_chunks addObject:chunk];
        state->_keptBytes += length;
    } else {
        if (room > 0) {
            [state->_chunks addObject:hopi_us_prefix(chunk, (NSUInteger)room)];
            state->_keptBytes += room;
        }
        state->_recent[state->_recentNext] = chunk;
        state->_recentNext = (state->_recentNext + 1) % HOPI_US_RING_SIZE;
    }
    int64_t retained = state->_keptBytes;
    os_unfair_lock_unlock(&state->_lock);
    hopi_us_raise_high_water(retained);
}

/** Hook 3, after the original. */
static void hopi_us_on_finish(id connection, id error) {
    NSURLSessionTask *task = hopi_us_connection_task(connection);
    if (task == nil) return;
    HopiUSTask *state = hopi_us_state(task);
    if (state == nil || state->_path != HOPI_US_PATH_BYTES) return;
    hopi_us_finish(task, state, [error isKindOfClass:NSError.class] ? error : nil);
}

/**
 * Hook 4, after the original factory returned the task. The marker is made only for a task that
 * has none yet, so a public factory that calls a hooked private one copies the body once.
 */
static void hopi_us_on_upload(id task, id (^make)(id argument), id argument) {
    if (![task isKindOfClass:NSURLSessionTask.class]) return;
    if (objc_getAssociatedObject(task, &kUploadBodyKey) != nil || hopi_us_state(task) != nil) return;
    id marker = make(argument);
    if (marker == nil) return;
    os_unfair_lock_lock(&gAttachLock);
    if (objc_getAssociatedObject(task, &kUploadBodyKey) == nil) {
        objc_setAssociatedObject(task, &kUploadBodyKey, marker, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
    }
    os_unfair_lock_unlock(&gAttachLock);
}

#pragma mark - Rules: delays and request edits

/** The plan of task, or nil. */
static HopiUSPlan *hopi_us_plan(NSURLSessionTask *task) {
    id plan = objc_getAssociatedObject(task, &kPlanKey);
    return [plan isKindOfClass:HopiUSPlan.class] ? plan : nil;
}

static void hopi_us_attach_plan(id task, HopiUSRule *rule) {
    if (rule == nil || ![task isKindOfClass:NSURLSessionTask.class]) return;
    HopiUSPlan *plan = [HopiUSPlan new];
    plan->_rule = rule;
    objc_setAssociatedObject(task, &kPlanKey, plan, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

/** The raw query after the edit: parameters whose decoded name is dropped go, the edit's parts are appended. */
static NSString *hopi_us_edit_query(NSString *query, HopiUSRule *rule) {
    NSMutableArray<NSString *> *parts = [NSMutableArray array];
    for (NSString *part in [query componentsSeparatedByString:@"&"]) {
        if (part.length == 0) continue;
        NSRange equals = [part rangeOfString:@"="];
        NSString *name = hopi_us_percent_decode(equals.location == NSNotFound ? part : [part substringToIndex:equals.location]);
        if (![rule->_dropQuery containsObject:name]) [parts addObject:part];
    }
    [parts addObjectsFromArray:rule->_addQuery];
    return parts.count > 0 ? [parts componentsJoinedByString:@"&"] : nil;
}

static NSString *const kRuledProperty = @"dev.koga.hopi.ruled";

/** Whether rule's edit moves a request for url to another origin (host or port): Kotlin's EditedRequest.originChanged. */
static bool hopi_us_edit_changes_origin(HopiUSRule *rule, NSURL *url) {
    if (rule->_editHost != nil && (url.host == nil || [rule->_editHost caseInsensitiveCompare:url.host] != NSOrderedSame)) return true;
    if (rule->_editPort < 0) return false;
    NSInteger defaultPort = [url.scheme caseInsensitiveCompare:@"https"] == NSOrderedSame ? 443 : 80;
    return rule->_editPort != (url.port != nil ? url.port.integerValue : defaultPort);
}

/**
 * The first header the session's configuration adds (HTTPAdditionalHeaders) whose lowercased name
 * is in strip and not in keep, or nil. URLSession merges those headers into a task's request below
 * the factory hooks, wherever the request lacks the field, so an edit to another origin cannot
 * remove them: such an edit is not applied (docs/design/rules.md, "Edit"). A name the edit sets or
 * adds itself (keep) is a field of the request, which the configuration's value never overrides.
 */
static NSString *hopi_us_session_adds(id session, NSSet<NSString *> *strip, NSSet<NSString *> *keep) {
    if (strip.count == 0 || ![session isKindOfClass:NSURLSession.class]) return nil;
    NSDictionary *additional = ((NSURLSession *)session).configuration.HTTPAdditionalHeaders;
    for (id name in additional) {
        if (![name isKindOfClass:NSString.class]) continue;
        NSString *lower = ((NSString *)name).lowercaseString;
        if ([strip containsObject:lower] && ![keep containsObject:lower]) return name;
    }
    return nil;
}

/**
 * Kotlin's RequestEdits.apply on a copy of request: the scheme never changes; an origin change
 * (host or port) removes every original value of the credentials and redacted headers, so only
 * the values the edit supplies reach the other host; header edits remove, then append. The copy is Hopi's own request, marked so that a factory
 * that calls another factory does not apply the rule twice. replaceBody: the edit's body becomes
 * HTTPBody (never on a stream body; an upload's data is replaced by its factory hook instead).
 */
static NSURLRequest *hopi_us_edited(NSURLRequest *request, HopiUSRule *rule, bool replaceBody) {
    NSMutableURLRequest *edited = [request mutableCopy];
    if (rule->_editMethod != nil) edited.HTTPMethod = rule->_editMethod;
    NSURL *url = request.URL.absoluteURL;
    bool originChanged = hopi_us_edit_changes_origin(rule, url);
    if (rule->_editHost != nil || rule->_editPort >= 0 || rule->_editPath != nil || rule->_dropQuery.count > 0 || rule->_addQuery.count > 0) {
        NSURLComponents *components = [NSURLComponents componentsWithURL:url resolvingAgainstBaseURL:YES];
        if (components != nil) {
            NSInteger defaultPort = [url.scheme caseInsensitiveCompare:@"https"] == NSOrderedSame ? 443 : 80;
            if (rule->_editHost != nil) {
                if ([rule->_editHost containsString:@":"]) {
                    components.percentEncodedHost = [NSString stringWithFormat:@"[%@]", rule->_editHost];
                } else {
                    components.host = rule->_editHost;
                }
            }
            if (rule->_editPort >= 0) components.port = rule->_editPort == defaultPort && url.port == nil ? nil : @(rule->_editPort);
            if (rule->_editPath != nil) components.percentEncodedPath = rule->_editPath;
            if (rule->_dropQuery.count > 0 || rule->_addQuery.count > 0) {
                components.percentEncodedQuery = hopi_us_edit_query(components.percentEncodedQuery, rule);
            }
            NSURL *editedURL = components.URL;
            if (editedURL != nil) edited.URL = editedURL;
        }
    }
    NSDictionary<NSString *, NSString *> *fields = request.allHTTPHeaderFields;
    for (NSString *name in fields) {
        NSString *lower = name.lowercaseString;
        // Across origins every original credential goes, whatever the edit sets or adds after.
        if ([rule->_removeHeaders containsObject:lower] || (originChanged && [rule->_stripHeaders containsObject:lower])) {
            [edited setValue:nil forHTTPHeaderField:name];
        }
    }
    for (NSUInteger i = 0; i + 1 < rule->_addHeaders.count; i += 2) {
        [edited addValue:rule->_addHeaders[i + 1] forHTTPHeaderField:rule->_addHeaders[i]];
    }
    if (replaceBody && rule->_editBody != nil && request.HTTPBodyStream == nil) edited.HTTPBody = rule->_editBody;
    [NSURLProtocol setProperty:@YES forKey:kRuledProperty inRequest:edited];
    return edited;
}

static Class gBackgroundSessionClass;

/**
 * Hook 8's plan for a request at creation: the first matching rule, claimed, when it is an edit
 * or a delay; a mock or fail first match is left to the protocol, which claims when the task
 * starts loading. Writes the rule to *planned and returns the request to create the task with
 * (the edited copy, or request itself).
 */
static NSURLRequest *hopi_us_factory_plan(id session, NSURLRequest *request, bool replaceBody, HopiUSRule *__strong *planned) {
    *planned = nil;
    HopiUSRuleSet *set = hopi_us_rules();
    if (set == nil || !set->_plans) return request;
    if (gBackgroundSessionClass != nil && [session isKindOfClass:gBackgroundSessionClass]) return request;
    if (![request isKindOfClass:NSURLRequest.class] || [NSURLProtocol propertyForKey:kRuledProperty inRequest:request] != nil) return request;
    HopiUSView *view = hopi_us_view(request);
    if (view == nil) return request;
    for (HopiUSRule *rule in set->_rules) {
        if (!hopi_us_rule_matches(rule, view)) continue;
        if (rule->_action == HOPI_US_ACTION_MOCK || rule->_action == HOPI_US_ACTION_FAIL) {
            if (hopi_us_exhausted(rule)) continue;
            return request;
        }
        // An edit to another origin on a session that adds a credential it cannot remove: the
        // request goes unedited and unclaimed, never with the app's credential to the other host.
        if (rule->_action == HOPI_US_ACTION_EDIT && hopi_us_edit_changes_origin(rule, view->_url) &&
            hopi_us_session_adds(session, rule->_stripHeaders, rule->_keepHeaders) != nil) {
            return request;
        }
        if (!hopi_us_claim(rule)) continue;
        *planned = rule;
        return rule->_action == HOPI_US_ACTION_EDIT ? hopi_us_edited(request, rule, replaceBody) : request;
    }
    return request;
}

// >0 while a factory that planned runs its original: an inner hooked factory passes through.
static __thread int gFactoryDepth;

/**
 * A request or URL factory's argument after hook 8: the edited request, else argument itself.
 * A URL factory's edit is sent through requestSelector (the matching request factory), since a
 * URL cannot carry headers; that is only planned when the session answers it.
 */
static id hopi_us_factory_argument(id session, id argument, SEL requestSelector, bool replaceBody, HopiUSRule *__strong *planned) {
    *planned = nil;
    if (!atomic_load_explicit(&gHasRules, memory_order_relaxed) || gFactoryDepth > 0) return argument;
    if (requestSelector == NULL) return hopi_us_factory_plan(session, argument, replaceBody, planned);
    if (![argument isKindOfClass:NSURL.class] || ![session respondsToSelector:requestSelector]) return argument;
    NSURLRequest *request = [NSURLRequest requestWithURL:argument];
    NSURLRequest *sent = hopi_us_factory_plan(session, request, replaceBody, planned);
    return sent != request ? sent : argument;
}

#pragma mark - Installation

/** The method `selector` defined by `cls` itself (not inherited), or NULL. */
static Method hopi_us_own_method(Class cls, SEL selector) {
    unsigned int count = 0;
    Method *methods = class_copyMethodList(cls, &count);
    Method found = NULL;
    for (unsigned int i = 0; i < count; i++) {
        if (method_getName(methods[i]) == selector) {
            found = methods[i];
            break;
        }
    }
    free(methods);
    return found;
}

static bool hopi_us_encoding_is(Method method, const char *expected) {
    const char *encoding = method_getTypeEncoding(method);
    return encoding != NULL && strcmp(encoding, expected) == 0;
}

bool hopi_us_self_check(void) {
    // The test switch stands for an OS whose private connection class changed.
    if (atomic_load(&gForceMetadata)) return false;
    Class local = NSClassFromString(@"__NSCFURLLocalSessionConnection");
    Class base = NSClassFromString(@"__NSCFURLSessionConnection");
    if (local == nil || base == nil || ![local isSubclassOfClass:base]) return false;
    Method data = hopi_us_own_method(local, NSSelectorFromString(@"_didReceiveData:"));
    Method finish = hopi_us_own_method(local, NSSelectorFromString(@"_didFinishWithError:"));
    if (data == NULL || finish == NULL) return false;
    if (!hopi_us_encoding_is(data, kDidReceiveDataEncoding) || !hopi_us_encoding_is(finish, kDidFinishEncoding)) return false;
    Ivar ivar = class_getInstanceVariable(base, "_task");
    const char *type = ivar != NULL ? ivar_getTypeEncoding(ivar) : NULL;
    return type != NULL && strcmp(type, "@\"NSURLSessionTask\"") == 0;
}

// Replaced implementations keep the previous one per (class, selector), set atomically by
// method_setImplementation; until it returns, the slot holds the implementation read before.
typedef struct {
    _Atomic(IMP) previous;
} hopi_us_slot;

static hopi_us_slot *hopi_us_new_slot(Method method) {
    hopi_us_slot *slot = calloc(1, sizeof(hopi_us_slot));
    atomic_store(&slot->previous, method_getImplementation(method));
    return slot;
}

static void hopi_us_replace(Method method, hopi_us_slot *slot, id block) {
    IMP previous = method_setImplementation(method, imp_implementationWithBlock(block));
    atomic_store(&slot->previous, previous);
}

/**
 * Hook 9, in hook 1 while marks exist: the first resume of a task whose plan has a delay creates
 * the capture state now (the record's duration includes the delay) and calls the original resume
 * after the delay, on the capture queue. The task stays suspended meanwhile and its session's
 * timeouts start at the real resume. A cancel during the delay completes the task at once and the
 * late resume does nothing (the record is ended here then: no connection will); a suspend drops
 * the pending resume (hook 8's -suspend). Returns true when the call was taken over.
 */
static bool hopi_us_defers_resume(NSURLSessionTask *task, hopi_us_slot *slot, SEL selector) {
    HopiUSPlan *plan = hopi_us_plan(task);
    if (plan == nil || plan->_rule->_delayRequestMs <= 0) return false;
    os_unfair_lock_lock(&gPlanLock);
    int32_t resume = plan->_resume;
    if (resume == HOPI_US_RESUME_IDLE && task.state != NSURLSessionTaskStateSuspended) resume = HOPI_US_RESUME_DONE;
    if (resume != HOPI_US_RESUME_IDLE) {
        os_unfair_lock_unlock(&gPlanLock);
        // A second resume during the delay changes nothing: the task resumes when it ends.
        return resume == HOPI_US_RESUME_PENDING;
    }
    plan->_resume = HOPI_US_RESUME_PENDING;
    uint64_t generation = ++plan->_generation;
    os_unfair_lock_unlock(&gPlanLock);
    HOPI_US_TIMED(0, hopi_us_on_resume(task));
    int64_t delayMs = plan->_rule->_delayRequestMs;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * (int64_t)NSEC_PER_MSEC), gQueue, ^{
        os_unfair_lock_lock(&gPlanLock);
        bool run = plan->_resume == HOPI_US_RESUME_PENDING && plan->_generation == generation;
        if (run) plan->_resume = HOPI_US_RESUME_DONE;
        os_unfair_lock_unlock(&gPlanLock);
        if (!run) return;
        if (task.state == NSURLSessionTaskStateCompleted) {
            HopiUSTask *state = hopi_us_state(task);
            if (state != nil) hopi_us_finish(task, state, task.error);
            return;
        }
        ((void (*)(id, SEL))atomic_load(&slot->previous))(task, selector);
    });
    return true;
}

static void hopi_us_hook_resume(Class cls) {
    SEL selector = @selector(resume);
    Method method = hopi_us_own_method(cls, selector);
    if (method == NULL) return;
    hopi_us_slot *slot = hopi_us_new_slot(method);
    hopi_us_replace(method, slot, ^(id task) {
        if (atomic_load_explicit(&gTaskMarks, memory_order_relaxed) > 0 && hopi_us_defers_resume(task, slot, selector)) return;
        HOPI_US_TIMED(0, hopi_us_on_resume(task));
        ((void (*)(id, SEL))atomic_load(&slot->previous))(task, selector);
    });
}

static void hopi_us_install_resume(void) {
    // Probe tasks of every kind from a private session, never resumed, walked for the classes
    // that define their own resume: each is replaced once.
    NSURLSession *probe = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.ephemeralSessionConfiguration];
    NSURL *url = [NSURL URLWithString:@"http://127.0.0.1:9/"];
    NSURLRequest *request = [NSURLRequest requestWithURL:url];
    NSArray<NSURLSessionTask *> *tasks = @[
        [probe dataTaskWithRequest:request],
        [probe uploadTaskWithRequest:request fromData:[NSData data]],
        [probe downloadTaskWithRequest:request],
    ];
    NSMutableSet<Class> *done = [NSMutableSet set];
    for (NSURLSessionTask *task in tasks) {
        for (Class cls = object_getClass(task); cls != nil; cls = class_getSuperclass(cls)) {
            if ([done containsObject:cls]) continue;
            [done addObject:cls];
            hopi_us_hook_resume(cls);
            if (cls == NSURLSessionTask.class) break;
        }
    }
    for (NSURLSessionTask *task in tasks) [task cancel];
    [probe invalidateAndCancel];
    // The background task chain, so a background task reaches hook 1 even if it defines its own.
    Class background = NSClassFromString(@"__NSCFBackgroundSessionTask");
    for (Class cls = background; cls != nil; cls = class_getSuperclass(cls)) {
        if ([done containsObject:cls]) continue;
        [done addObject:cls];
        hopi_us_hook_resume(cls);
    }
}

static void hopi_us_install_connection(void) {
    Class local = NSClassFromString(@"__NSCFURLLocalSessionConnection");
    SEL dataSelector = NSSelectorFromString(@"_didReceiveData:");
    SEL finishSelector = NSSelectorFromString(@"_didFinishWithError:");
    Method data = hopi_us_own_method(local, dataSelector);
    Method finish = hopi_us_own_method(local, finishSelector);
    hopi_us_slot *dataSlot = hopi_us_new_slot(data);
    hopi_us_slot *finishSlot = hopi_us_new_slot(finish);
    hopi_us_replace(data, dataSlot, ^(id connection, id chunk) {
        ((void (*)(id, SEL, id))atomic_load(&dataSlot->previous))(connection, dataSelector, chunk);
        HOPI_US_TIMED(1, hopi_us_on_data(connection, chunk));
    });
    hopi_us_replace(finish, finishSlot, ^(id connection, id error) {
        ((void (*)(id, SEL, id))atomic_load(&finishSlot->previous))(connection, finishSelector, error);
        HOPI_US_TIMED(2, hopi_us_on_finish(connection, error));
    });
}

/** The classes of NSURLSession's chain (and the local session's) that define `selector` themselves. */
static NSArray<Class> *hopi_us_session_classes(SEL selector) {
    NSMutableArray<Class> *found = [NSMutableArray array];
    Class local = NSClassFromString(@"__NSURLSessionLocal");
    NSMutableArray<Class> *chain = [NSMutableArray array];
    for (Class cls = local; cls != nil && cls != NSObject.class; cls = class_getSuperclass(cls)) [chain addObject:cls];
    if (![chain containsObject:NSURLSession.class]) [chain addObject:NSURLSession.class];
    for (Class cls in chain) {
        if (hopi_us_own_method(cls, selector) != NULL) [found addObject:cls];
    }
    return found;
}

/**
 * An upload factory's request after hook 8's edit; an edit's body replaces *data when the upload
 * sends data (dataBody), never a file or a stream.
 */
static id hopi_us_upload_plan(id session, id request, id __strong *data, bool dataBody, HopiUSRule *__strong *planned) {
    *planned = nil;
    if (!atomic_load_explicit(&gHasRules, memory_order_relaxed) || gFactoryDepth > 0) return request;
    NSURLRequest *sent = hopi_us_factory_plan(session, request, false, planned);
    if (*planned != nil && dataBody && (*planned)->_editBody != nil) *data = (*planned)->_editBody;
    return sent;
}

static void hopi_us_hook_upload2(NSString *name, bool dataBody, id (^marker)(id argument)) {
    SEL selector = NSSelectorFromString(name);
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@32@0:8@16@24")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id request, id argument) {
            HopiUSRule *rule = nil;
            id body = argument;
            id sent = hopi_us_upload_plan(session, request, &body, dataBody, &rule);
            if (rule != nil) gFactoryDepth++;
            id task = ((id (*)(id, SEL, id, id))atomic_load(&slot->previous))(session, selector, sent, body);
            if (rule != nil) gFactoryDepth--;
            hopi_us_on_upload(task, marker, body);
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

static void hopi_us_hook_upload3(NSString *name, bool dataBody, id (^marker)(id argument)) {
    SEL selector = NSSelectorFromString(name);
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@40@0:8@16@24@?32")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id request, id argument, id handler) {
            HopiUSRule *rule = nil;
            id body = argument;
            id sent = hopi_us_upload_plan(session, request, &body, dataBody, &rule);
            if (rule != nil) gFactoryDepth++;
            id task = ((id (*)(id, SEL, id, id, id))atomic_load(&slot->previous))(session, selector, sent, body, handler);
            if (rule != nil) gFactoryDepth--;
            hopi_us_on_upload(task, marker, body);
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

static void hopi_us_hook_upload4(NSString *name, bool dataBody, id (^marker)(id argument)) {
    SEL selector = NSSelectorFromString(name);
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, kPrivateUploadEncoding)) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id request, id argument, id delegate, id handler) {
            HopiUSRule *rule = nil;
            id body = argument;
            id sent = hopi_us_upload_plan(session, request, &body, dataBody, &rule);
            if (rule != nil) gFactoryDepth++;
            id task = ((id (*)(id, SEL, id, id, id, id))atomic_load(&slot->previous))(session, selector, sent, body, delegate, handler);
            if (rule != nil) gFactoryDepth--;
            hopi_us_on_upload(task, marker, body);
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

static void hopi_us_hook_streamed(void) {
    SEL selector = @selector(uploadTaskWithStreamedRequest:);
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@24@0:8@16")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id request) {
            HopiUSRule *rule = nil;
            id unused = nil;
            id sent = hopi_us_upload_plan(session, request, &unused, false, &rule);
            if (rule != nil) gFactoryDepth++;
            id task = ((id (*)(id, SEL, id))atomic_load(&slot->previous))(session, selector, sent);
            if (rule != nil) gFactoryDepth--;
            hopi_us_on_upload(task, ^id(__unused id argument) {
                return @(HOPI_US_BODY_STREAM);
            }, nil);
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

static void hopi_us_install_uploads(void) {
    // An immutable NSData's copy is the same object. A mutable one is copied, so a later change by
    // the app does not change the record, but never more than the cap on the caller's thread: past
    // it only its first maxBodyBytes and its length are kept. (An immutable __NSCFData reports
    // itself mutable and so takes the bounded copy; no mutable class reports immutable.)
    id (^data)(id) = ^id(id argument) {
        if (![argument isKindOfClass:NSData.class]) return nil;
        NSData *bytes = argument;
        HopiUSBody *body = [HopiUSBody new];
        body->_length = (int64_t)bytes.length;
        body->_bytes = ![bytes isKindOfClass:NSMutableData.class] || body->_length <= gMaxBodyBytes
                           ? [bytes copy]
                           : hopi_us_prefix(bytes, (NSUInteger)gMaxBodyBytes);
        return body;
    };
    id (^file)(id) = ^id(__unused id argument) {
        return @(HOPI_US_BODY_FILE);
    };
    hopi_us_hook_upload2(@"uploadTaskWithRequest:fromData:", true, data);
    hopi_us_hook_upload3(@"uploadTaskWithRequest:fromData:completionHandler:", true, data);
    hopi_us_hook_upload4(@"_uploadTaskWithRequest:fromData:delegate:completionHandler:", true, data);
    hopi_us_hook_upload2(@"uploadTaskWithRequest:fromFile:", false, file);
    hopi_us_hook_upload3(@"uploadTaskWithRequest:fromFile:completionHandler:", false, file);
    hopi_us_hook_upload4(@"_uploadTaskWithRequest:fromFile:delegate:completionHandler:", false, file);
    hopi_us_hook_streamed();
}

#pragma mark - Rules: the protocol (mock and fail)

static dispatch_queue_t gRuleQueue;
static os_unfair_lock gDecisionLock = OS_UNFAIR_LOCK_INIT;

/** A fail rule's error, as the system reports it: NSURLErrorDomain, its wording, the failing URL. */
static NSError *hopi_us_fail_error(int32_t kind, NSURL *url) {
    NSInteger code;
    NSString *description;
    switch (kind) {
        case HOPI_US_FAIL_TIMEOUT:
            code = NSURLErrorTimedOut;
            description = @"The request timed out.";
            break;
        case HOPI_US_FAIL_RESET:
            code = NSURLErrorNetworkConnectionLost;
            description = @"The network connection was lost.";
            break;
        default:
            code = NSURLErrorCannotFindHost;
            description = @"A server with the specified hostname could not be found.";
            break;
    }
    NSMutableDictionary<NSString *, id> *info = [NSMutableDictionary dictionaryWithObject:description forKey:NSLocalizedDescriptionKey];
    if (url != nil) {
        info[NSURLErrorFailingURLErrorKey] = url;
        info[NSURLErrorFailingURLStringErrorKey] = url.absoluteString;
    }
    return [NSError errorWithDomain:NSURLErrorDomain code:code userInfo:info];
}

/**
 * The protocol's rule for a task, claimed: the first matching rule when it is a mock or fail. One
 * rule per request: an edit or delay first match was applied at creation or not at all (the task
 * came from an unhooked factory), and is never replaced by a later mock.
 */
static HopiUSRule *hopi_us_protocol_rule(NSURLSessionTask *task) {
    HopiUSRuleSet *set = hopi_us_rules();
    if (set == nil) return nil;
    if (atomic_load_explicit(&gTaskMarks, memory_order_relaxed) > 0 && hopi_us_plan(task) != nil) return nil;
    if (hopi_us_is_background(task)) return nil;
    HopiUSView *view = hopi_us_view(task.currentRequest ?: task.originalRequest);
    if (view == nil) return nil;
    for (HopiUSRule *rule in set->_rules) {
        if (!hopi_us_rule_matches(rule, view)) continue;
        if (rule->_action != HOPI_US_ACTION_MOCK && rule->_action != HOPI_US_ACTION_FAIL) {
            if (hopi_us_exhausted(rule)) continue;
            return nil;
        }
        if (hopi_us_claim(rule)) return rule;
    }
    return nil;
}

/**
 * Hook 7. Claims only tasks a mock or fail rule matches, when they start loading (so a task
 * created before the rule and resumed after it is mocked), and answers them itself.
 */
@interface HopiUSRuleProtocol : NSURLProtocol
@end

@implementation HopiUSRuleProtocol {
    dispatch_block_t _pending;
    CFRunLoopRef _loop;
    bool _stopped;
}

+ (BOOL)canInitWithRequest:(__unused NSURLRequest *)request {
    // NSURLConnection and the other loaders sharing registered classes are not sources Hopi
    // records: a rule applied there would leave no mark.
    return NO;
}

+ (BOOL)canInitWithTask:(NSURLSessionTask *)task {
    if (atomic_load_explicit(&gTiming, memory_order_relaxed)) atomic_fetch_add(&gCanInitCalls, 1);
    if (!atomic_load_explicit(&gHasRules, memory_order_acquire)) return NO;
    os_unfair_lock_lock(&gDecisionLock);
    BOOL claimed = objc_getAssociatedObject(task, &kDecisionKey) != nil;
    if (!claimed) {
        HopiUSRule *rule = hopi_us_protocol_rule(task);
        if (rule != nil) {
            objc_setAssociatedObject(task, &kDecisionKey, rule, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            claimed = YES;
            // The record's mark: hook 1 made the state when the task was resumed.
            HopiUSTask *state = hopi_us_state(task);
            if (state != nil) {
                os_unfair_lock_lock(&state->_lock);
                state->_ruleId = rule->_id;
                state->_ruleApplied = rule->_applied;
                state->_byProtocol = true;
                os_unfair_lock_unlock(&state->_lock);
            }
        }
    }
    os_unfair_lock_unlock(&gDecisionLock);
    return claimed;
}

+ (NSURLRequest *)canonicalRequestForRequest:(NSURLRequest *)request {
    return request;
}

/** Serves the rule now: a response from no network, or the platform's own failure. */
- (void)hopi_serve:(HopiUSRule *)rule {
    id<NSURLProtocolClient> client = self.client;
    NSURLRequest *request = self.request;
    if (rule->_action == HOPI_US_ACTION_FAIL) {
        if (rule->_failKind == HOPI_US_FAIL_CANCEL) {
            // The task ends with -999 exactly as an app cancel ends it.
            [self.task cancel];
            return;
        }
        [client URLProtocol:self didFailWithError:hopi_us_fail_error(rule->_failKind, request.URL)];
        return;
    }
    NSHTTPURLResponse *response = [[NSHTTPURLResponse alloc] initWithURL:request.URL statusCode:rule->_status HTTPVersion:@"HTTP/1.1" headerFields:rule->_mockHeaders];
    // Never cached: a cache that saw a mock would keep serving it after the rule is gone.
    [client URLProtocol:self didReceiveResponse:response cacheStoragePolicy:NSURLCacheStorageNotAllowed];
    bool head = [request.HTTPMethod isEqualToString:@"HEAD"];
    if (!head && rule->_body.length > 0) [client URLProtocol:self didLoadData:rule->_body];
    [client URLProtocolDidFinishLoading:self];
}

- (void)startLoading {
    NSURLSessionTask *task = self.task;
    HopiUSRule *rule = task != nil ? objc_getAssociatedObject(task, &kDecisionKey) : nil;
    if (![rule isKindOfClass:HopiUSRule.class]) {
        [self.client URLProtocol:self didFailWithError:[NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorUnknown userInfo:nil]];
        return;
    }
    int64_t delayMs = rule->_delayRequestMs + rule->_delayResponseMs;
    if (delayMs <= 0) {
        [self hopi_serve:rule];
        return;
    }
    // The client is called on the thread that called startLoading: the delay runs on Hopi's
    // queue, and the answer is performed on this thread's run loop, unless stopLoading (the
    // task's cancel) came first.
    _loop = (CFRunLoopRef)CFRetain(CFRunLoopGetCurrent());
    __weak HopiUSRuleProtocol *weakSelf = self;
    dispatch_block_t fire = dispatch_block_create(0, ^{
        HopiUSRuleProtocol *strongSelf = weakSelf;
        if (strongSelf == nil) return;
        CFRunLoopRef loop = strongSelf->_loop;
        CFRunLoopPerformBlock(loop, kCFRunLoopCommonModes, ^{
            if (!strongSelf->_stopped) [strongSelf hopi_serve:rule];
        });
        CFRunLoopWakeUp(loop);
    });
    _pending = fire;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, delayMs * (int64_t)NSEC_PER_MSEC), gRuleQueue, fire);
}

- (void)stopLoading {
    _stopped = true;
    if (_pending != nil) {
        dispatch_block_cancel(_pending);
        _pending = nil;
    }
}

- (void)dealloc {
    if (_loop != NULL) CFRelease(_loop);
}
@end

/** Hook 6's configuration: a copy with the rule protocol first, unless it is a background one or has it already. */
static id hopi_us_ruled_configuration(id configuration) {
    if (![configuration isKindOfClass:NSURLSessionConfiguration.class]) return configuration;
    NSURLSessionConfiguration *original = configuration;
    // Custom protocols never run in a background session (out of process).
    if (original.identifier != nil) return configuration;
    NSArray<Class> *classes = original.protocolClasses ?: @[];
    if ([classes containsObject:HopiUSRuleProtocol.class]) return configuration;
    NSURLSessionConfiguration *copy = [original copy];
    copy.protocolClasses = [@[ HopiUSRuleProtocol.class ] arrayByAddingObjectsFromArray:classes];
    return copy;
}

static const char *const kSessionFactoryEncoding = "@40@0:8@16@24@32";

/** Hook 6, on the funnel both session initializers use; false when it fails the self-check. */
static bool hopi_us_install_session_factory(void) {
    SEL selector = @selector(sessionWithConfiguration:delegate:delegateQueue:);
    Method method = hopi_us_own_method(object_getClass(NSURLSession.class), selector);
    if (method == NULL || !hopi_us_encoding_is(method, kSessionFactoryEncoding)) return false;
    hopi_us_slot *slot = hopi_us_new_slot(method);
    hopi_us_replace(method, slot, ^id(id cls, id configuration, id delegate, id queue) {
        return ((id (*)(id, SEL, id, id, id))atomic_load(&slot->previous))(cls, selector, hopi_us_ruled_configuration(configuration), delegate, queue);
    });
    return true;
}

#pragma mark - Rules: task factories (edit and delay)

static _Atomic bool gFactoriesInstalled;

/** Request and URL factories taking one argument: dataTaskWithRequest:, downloadTaskWithURL:, … */
static void hopi_us_hook_factory1(NSString *name, NSString *requestName) {
    SEL selector = NSSelectorFromString(name);
    SEL requestSelector = requestName != nil ? NSSelectorFromString(requestName) : NULL;
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@24@0:8@16")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id argument) {
            HopiUSRule *rule = nil;
            id sent = hopi_us_factory_argument(session, argument, requestSelector, true, &rule);
            if (rule == nil) return ((id (*)(id, SEL, id))atomic_load(&slot->previous))(session, selector, argument);
            gFactoryDepth++;
            id task = sent != argument && requestSelector != NULL
                          ? ((id (*)(id, SEL, id))objc_msgSend)(session, requestSelector, sent)
                          : ((id (*)(id, SEL, id))atomic_load(&slot->previous))(session, selector, sent);
            gFactoryDepth--;
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

/** The same with a completion handler: dataTaskWithRequest:completionHandler:, … */
static void hopi_us_hook_factory2(NSString *name, NSString *requestName) {
    SEL selector = NSSelectorFromString(name);
    SEL requestSelector = requestName != nil ? NSSelectorFromString(requestName) : NULL;
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@32@0:8@16@?24")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id argument, id handler) {
            HopiUSRule *rule = nil;
            id sent = hopi_us_factory_argument(session, argument, requestSelector, true, &rule);
            if (rule == nil) return ((id (*)(id, SEL, id, id))atomic_load(&slot->previous))(session, selector, argument, handler);
            gFactoryDepth++;
            id task = sent != argument && requestSelector != NULL
                          ? ((id (*)(id, SEL, id, id))objc_msgSend)(session, requestSelector, sent, handler)
                          : ((id (*)(id, SEL, id, id))atomic_load(&slot->previous))(session, selector, sent, handler);
            gFactoryDepth--;
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

/** The private ones with a per-task delegate, which Swift's async APIs use: _dataTaskWithRequest:delegate:completionHandler:, … */
static void hopi_us_hook_factory3(NSString *name, NSString *requestName) {
    SEL selector = NSSelectorFromString(name);
    SEL requestSelector = requestName != nil ? NSSelectorFromString(requestName) : NULL;
    for (Class cls in hopi_us_session_classes(selector)) {
        Method method = hopi_us_own_method(cls, selector);
        if (!hopi_us_encoding_is(method, "@40@0:8@16@24@?32")) continue;
        hopi_us_slot *slot = hopi_us_new_slot(method);
        hopi_us_replace(method, slot, ^id(id session, id argument, id delegate, id handler) {
            HopiUSRule *rule = nil;
            id sent = hopi_us_factory_argument(session, argument, requestSelector, true, &rule);
            if (rule == nil) return ((id (*)(id, SEL, id, id, id))atomic_load(&slot->previous))(session, selector, argument, delegate, handler);
            gFactoryDepth++;
            id task = sent != argument && requestSelector != NULL
                          ? ((id (*)(id, SEL, id, id, id))objc_msgSend)(session, requestSelector, sent, delegate, handler)
                          : ((id (*)(id, SEL, id, id, id))atomic_load(&slot->previous))(session, selector, sent, delegate, handler);
            gFactoryDepth--;
            hopi_us_attach_plan(task, rule);
            return task;
        });
    }
}

/** -suspend while a delayed resume is pending: drops it, so the app's next resume resumes at once. */
static void hopi_us_hook_suspend(Class cls) {
    SEL selector = @selector(suspend);
    Method method = hopi_us_own_method(cls, selector);
    if (method == NULL) return;
    hopi_us_slot *slot = hopi_us_new_slot(method);
    hopi_us_replace(method, slot, ^(id task) {
        if (atomic_load_explicit(&gTaskMarks, memory_order_relaxed) > 0) {
            HopiUSPlan *plan = hopi_us_plan(task);
            if (plan != nil) {
                os_unfair_lock_lock(&gPlanLock);
                bool dropped = plan->_resume == HOPI_US_RESUME_PENDING;
                if (dropped) {
                    plan->_resume = HOPI_US_RESUME_DONE;
                    plan->_generation++;
                }
                os_unfair_lock_unlock(&gPlanLock);
                // Never really resumed: it is suspended already.
                if (dropped) return;
            }
        }
        ((void (*)(id, SEL))atomic_load(&slot->previous))(task, selector);
    });
}

/** Hook 8, once, the first time an edit or delay rule is published: an app that never adds one never pays for it. */
static void hopi_us_install_factories(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // -suspend on every class of the task chains that defines it, as hook 1 does for -resume.
        // The probe tasks are never planned: no rule may claim them.
        gFactoryDepth++;
        NSURLSession *probe = [NSURLSession sessionWithConfiguration:NSURLSessionConfiguration.ephemeralSessionConfiguration];
        NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:@"http://127.0.0.1:9/"]];
        NSArray<NSURLSessionTask *> *tasks = @[
            [probe dataTaskWithRequest:request],
            [probe uploadTaskWithRequest:request fromData:[NSData data]],
            [probe downloadTaskWithRequest:request],
        ];
        NSMutableSet<Class> *done = [NSMutableSet set];
        for (NSURLSessionTask *task in tasks) {
            for (Class cls = object_getClass(task); cls != nil; cls = class_getSuperclass(cls)) {
                if ([done containsObject:cls]) continue;
                [done addObject:cls];
                hopi_us_hook_suspend(cls);
                if (cls == NSURLSessionTask.class) break;
            }
        }
        for (NSURLSessionTask *task in tasks) [task cancel];
        [probe invalidateAndCancel];
        gFactoryDepth--;
        hopi_us_hook_factory1(@"dataTaskWithRequest:", nil);
        hopi_us_hook_factory1(@"dataTaskWithURL:", @"dataTaskWithRequest:");
        hopi_us_hook_factory2(@"dataTaskWithRequest:completionHandler:", nil);
        hopi_us_hook_factory2(@"dataTaskWithURL:completionHandler:", @"dataTaskWithRequest:completionHandler:");
        hopi_us_hook_factory3(@"_dataTaskWithRequest:delegate:completionHandler:", nil);
        hopi_us_hook_factory3(@"_dataTaskWithURL:delegate:completionHandler:", @"_dataTaskWithRequest:delegate:completionHandler:");
        hopi_us_hook_factory1(@"downloadTaskWithRequest:", nil);
        hopi_us_hook_factory1(@"downloadTaskWithURL:", @"downloadTaskWithRequest:");
        hopi_us_hook_factory2(@"downloadTaskWithRequest:completionHandler:", nil);
        hopi_us_hook_factory2(@"downloadTaskWithURL:completionHandler:", @"downloadTaskWithRequest:completionHandler:");
        hopi_us_hook_factory3(@"_downloadTaskWithRequest:delegate:completionHandler:", nil);
        hopi_us_hook_factory3(@"_downloadTaskWithURL:delegate:completionHandler:", @"_downloadTaskWithRequest:delegate:completionHandler:");
        atomic_store(&gFactoriesInstalled, true);
    });
}

int32_t hopi_us_start(int64_t max_body_bytes, hopi_us_callback on_started, hopi_us_callback on_finished) {
    static dispatch_once_t once;
    __block int32_t result = HOPI_US_ALREADY_STARTED;
    dispatch_once(&once, ^{
        gMaxBodyBytes = max_body_bytes < 0 ? 0 : max_body_bytes;
        gOnStarted = on_started;
        gOnFinished = on_finished;
        dispatch_queue_attr_t attributes = dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0);
        gQueue = dispatch_queue_create("dev.koga.hopi.urlsession-capture", attributes);
        gObserver = [HopiUSObserver new];
        gBytePath = hopi_us_self_check();
        if (gBytePath) {
            gTaskIvar = class_getInstanceVariable(NSClassFromString(@"__NSCFURLSessionConnection"), "_task");
            hopi_us_install_connection();
        }
        hopi_us_install_uploads();
        hopi_us_install_resume();
        atomic_fetch_add(&gInstallCount, 1);
        result = gBytePath ? HOPI_US_BYTE_PATH : HOPI_US_METADATA_PATH;
    });
    return result;
}

#pragma mark - Accessors (capture queue)

#define STATE(handle) ((__bridge HopiUSTask *)(handle))

void hopi_us_release(hopi_us_handle handle) { CFRelease(handle); }

int64_t hopi_us_store_id(hopi_us_handle handle) { return STATE(handle)->_storeId; }

void hopi_us_set_store_id(hopi_us_handle handle, int64_t id) { STATE(handle)->_storeId = id; }

int64_t hopi_us_started_at_ms(hopi_us_handle handle) { return STATE(handle)->_startedAtMs; }

int64_t hopi_us_duration_ns(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    return state->_endNs > state->_startNs ? state->_endNs - state->_startNs : 0;
}

NSString *hopi_us_method(hopi_us_handle handle) { return STATE(handle)->_request.HTTPMethod ?: @"GET"; }

NSString *hopi_us_url(hopi_us_handle handle) { return STATE(handle)->_request.URL.absoluteString ?: @""; }

/**
 * The URL as CFNetwork writes currentRequest without a redirect: scheme and host lowercased, an
 * empty path as "/". Nothing else changes (checked: dot segments, escapes, fragments stay).
 */
static NSString *hopi_us_canonical(NSURL *url) {
    if (url == nil) return nil;
    NSURLComponents *components = [NSURLComponents componentsWithURL:url resolvingAgainstBaseURL:YES];
    if (components == nil) return url.absoluteString;
    components.scheme = components.scheme.lowercaseString;
    if (components.percentEncodedHost != nil) {
        components.percentEncodedHost = components.percentEncodedHost.lowercaseString;
        if (components.percentEncodedPath.length == 0) components.percentEncodedPath = @"/";
    }
    return components.URL.absoluteString ?: url.absoluteString;
}

NSString *hopi_us_final_url(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    NSURL *current = state->_currentURL;
    if (current == nil) return nil;
    // No redirect, the common case: the same URL object or an equal one needs no canonical form.
    if (current == state->_request.URL || [current isEqual:state->_request.URL]) return nil;
    NSString *final = hopi_us_canonical(current);
    // A redirect changed the URL, not CFNetwork's canonical form of the original.
    if (final == nil || [final isEqualToString:hopi_us_canonical(state->_request.URL)]) return nil;
    return current.absoluteString;
}

static NSArray<NSString *> *hopi_us_flatten(NSDictionary *headers) {
    NSMutableArray<NSString *> *out = [NSMutableArray arrayWithCapacity:headers.count * 2];
    [headers enumerateKeysAndObjectsUsingBlock:^(id name, id value, __unused BOOL *stop) {
        [out addObject:[name description]];
        [out addObject:[value description]];
    }];
    return out;
}

NSArray<NSString *> *hopi_us_request_headers(hopi_us_handle handle) {
    return hopi_us_flatten(STATE(handle)->_request.allHTTPHeaderFields ?: @{});
}

int32_t hopi_us_request_body_kind(hopi_us_handle handle) { return STATE(handle)->_requestBodyKind; }

int64_t hopi_us_request_body_length(hopi_us_handle handle) { return STATE(handle)->_uploadLength; }

int64_t hopi_us_copy_request_body(hopi_us_handle handle, void *dst, int64_t cap) {
    NSData *body = STATE(handle)->_uploadBody;
    __block int64_t copied = 0;
    [body enumerateByteRangesUsingBlock:^(const void *bytes, NSRange range, BOOL *stop) {
        int64_t take = MIN((int64_t)range.length, cap - copied);
        if (take <= 0) {
            *stop = YES;
            return;
        }
        memcpy((uint8_t *)dst + copied, bytes, (size_t)take);
        copied += take;
    }];
    return copied;
}

int64_t hopi_us_bytes_sent(hopi_us_handle handle) { return STATE(handle)->_bytesSent; }

int32_t hopi_us_status(hopi_us_handle handle) {
    NSHTTPURLResponse *response = STATE(handle)->_response;
    return response == nil ? -1 : (int32_t)response.statusCode;
}

NSArray<NSString *> *hopi_us_response_headers(hopi_us_handle handle) {
    return hopi_us_flatten(STATE(handle)->_response.allHeaderFields ?: @{});
}

int32_t hopi_us_response_body_kind(hopi_us_handle handle) { return STATE(handle)->_responseKind; }

int64_t hopi_us_response_total_bytes(hopi_us_handle handle) { return STATE(handle)->_totalBytes; }

int64_t hopi_us_response_kept_bytes(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    int64_t kept = state->_keptBytes;
    os_unfair_lock_unlock(&state->_lock);
    return kept;
}

int64_t hopi_us_copy_response(hopi_us_handle handle, void *dst, int64_t cap) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    NSArray<NSData *> *chunks = [state->_chunks copy];
    os_unfair_lock_unlock(&state->_lock);
    __block int64_t copied = 0;
    for (NSData *chunk in chunks) {
        if (copied >= cap) break;
        // Regions in place: a non-contiguous dispatch_data is never flattened first.
        [chunk enumerateByteRangesUsingBlock:^(const void *bytes, NSRange range, BOOL *stop) {
            int64_t take = MIN((int64_t)range.length, cap - copied);
            if (take <= 0) {
                *stop = YES;
                return;
            }
            memcpy((uint8_t *)dst + copied, bytes, (size_t)take);
            copied += take;
        }];
    }
    return copied;
}

NSString *hopi_us_error(hopi_us_handle handle) {
    NSError *error = STATE(handle)->_error;
    if (error == nil) return nil;
    NSString *described = error.userInfo[NSLocalizedDescriptionKey];
    if (described == nil && [error.domain isEqualToString:NSURLErrorDomain]) described = hopi_us_error_name(error.code);
    if (described == nil) described = error.localizedDescription;
    return [NSString stringWithFormat:@"%@ %ld: %@", error.domain, (long)error.code, described];
}

bool hopi_us_cancelled(hopi_us_handle handle) {
    NSError *error = STATE(handle)->_error;
    return error != nil && [error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled;
}

NSString *hopi_us_protocol(hopi_us_handle handle) { return STATE(handle)->_protocol; }

void hopi_us_drop_bytes(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    state->_chunks = [NSMutableArray array];
    state->_keptBytes = 0;
    state->_uploadBody = nil;
    state->_request = nil;
    os_unfair_lock_unlock(&state->_lock);
}

#pragma mark - Rules: API

hopi_us_hits hopi_us_hits_new(void) { return (__bridge_retained void *)[HopiUSHits new]; }

void hopi_us_hits_release(hopi_us_hits hits) {
    if (hits != NULL) CFRelease(hits);
}

int64_t hopi_us_hits_value(hopi_us_hits hits) { return atomic_load(&((__bridge HopiUSHits *)hits)->_count); }

int32_t hopi_us_hits_claim(hopi_us_hits hits, int64_t times) { return hopi_us_claim_hits((__bridge HopiUSHits *)hits, times); }

int32_t hopi_us_rules_enable(hopi_us_exhausted_callback on_exhausted) {
    if (gQueue == nil) return 0;
    static dispatch_once_t once;
    static int32_t result;
    dispatch_once(&once, ^{
        gOnExhausted = on_exhausted;
        dispatch_queue_attr_t attributes = dispatch_queue_attr_make_with_qos_class(DISPATCH_QUEUE_SERIAL, QOS_CLASS_UTILITY, 0);
        gRuleQueue = dispatch_queue_create("dev.koga.hopi.urlsession-rules", attributes);
        gBackgroundSessionClass = NSClassFromString(@"__NSURLBackgroundSession");
        int32_t bits = 0;
        if (hopi_us_install_session_factory()) bits |= HOPI_US_RULES_SESSIONS;
        result = bits;
    });
    return result;
}

hopi_us_rules_builder hopi_us_rules_begin(void) {
    HopiUSRuleSet *set = [HopiUSRuleSet new];
    set->_rules = [NSMutableArray array];
    return (__bridge_retained void *)set;
}

#define BUILDER(builder) ((__bridge HopiUSRuleSet *)(builder))
#define CURRENT(builder) (BUILDER(builder)->_rules.lastObject)

void hopi_us_rules_add(hopi_us_rules_builder builder, int64_t rule_id, hopi_us_hits hits, int64_t times, NSString *applied, int32_t action) {
    HopiUSRule *rule = [HopiUSRule new];
    rule->_id = rule_id;
    rule->_hits = (__bridge HopiUSHits *)hits;
    rule->_times = times;
    rule->_applied = [applied copy];
    rule->_action = action;
    rule->_port = -1;
    rule->_editPort = -1;
    rule->_query = [NSMutableArray array];
    rule->_headers = [NSMutableArray array];
    [BUILDER(builder)->_rules addObject:rule];
}

void hopi_us_rules_match(hopi_us_rules_builder builder, NSArray<NSString *> *methods, NSString *scheme, int32_t port, NSString *host, NSString *path) {
    HopiUSRule *rule = CURRENT(builder);
    rule->_methods = methods.count > 0 ? [NSSet setWithArray:methods] : nil;
    rule->_scheme = scheme.lowercaseString;
    rule->_port = port;
    rule->_host = hopi_us_glob(host.lowercaseString, false);
    rule->_path = hopi_us_glob(path, true);
}

void hopi_us_rules_predicate(hopi_us_rules_builder builder, bool header, NSString *name, NSString *value, bool absent) {
    HopiUSPredicate *predicate = [HopiUSPredicate new];
    predicate->_name = [name copy];
    predicate->_value = hopi_us_glob(value, false);
    predicate->_absent = absent;
    HopiUSRule *rule = CURRENT(builder);
    [header ? rule->_headers : rule->_query addObject:predicate];
}

void hopi_us_rules_delay(hopi_us_rules_builder builder, int64_t before_request_ms, int64_t before_response_ms) {
    HopiUSRule *rule = CURRENT(builder);
    rule->_delayRequestMs = before_request_ms;
    rule->_delayResponseMs = before_response_ms;
}

void hopi_us_rules_mock(hopi_us_rules_builder builder, int32_t status, NSArray<NSString *> *headers, NSData *body) {
    HopiUSRule *rule = CURRENT(builder);
    rule->_status = status;
    rule->_body = [body copy];
    // One field per name, as NSHTTPURLResponse holds them: repeated names are joined with ", ".
    NSMutableDictionary<NSString *, NSString *> *fields = [NSMutableDictionary dictionary];
    NSMutableDictionary<NSString *, NSString *> *names = [NSMutableDictionary dictionary];
    for (NSUInteger i = 0; i + 1 < headers.count; i += 2) {
        NSString *lower = headers[i].lowercaseString;
        NSString *name = names[lower];
        if (name == nil) {
            names[lower] = headers[i];
            fields[headers[i]] = headers[i + 1];
        } else {
            fields[name] = [NSString stringWithFormat:@"%@, %@", fields[name], headers[i + 1]];
        }
    }
    if (status >= 200 && status != 204 && status != 304) fields[@"Content-Length"] = [NSString stringWithFormat:@"%lu", (unsigned long)body.length];
    rule->_mockHeaders = fields;
}

void hopi_us_rules_fail(hopi_us_rules_builder builder, int32_t kind) { CURRENT(builder)->_failKind = kind; }

void hopi_us_rules_edit(hopi_us_rules_builder builder, NSString *method, NSString *host, int32_t port, NSString *path,
                        NSArray<NSString *> *drop_query, NSArray<NSString *> *add_query, NSArray<NSString *> *remove_headers,
                        NSArray<NSString *> *keep_headers, NSArray<NSString *> *strip_headers, NSArray<NSString *> *add_headers,
                        NSData *body) {
    HopiUSRule *rule = CURRENT(builder);
    rule->_editMethod = [method copy];
    rule->_editHost = [host copy];
    rule->_editPort = port;
    rule->_editPath = [path copy];
    rule->_dropQuery = [NSSet setWithArray:drop_query ?: @[]];
    rule->_addQuery = [add_query copy] ?: @[];
    rule->_removeHeaders = [NSSet setWithArray:remove_headers ?: @[]];
    rule->_keepHeaders = [NSSet setWithArray:keep_headers ?: @[]];
    rule->_stripHeaders = [NSSet setWithArray:strip_headers ?: @[]];
    rule->_addHeaders = [add_headers copy] ?: @[];
    rule->_editBody = [body copy];
}

#undef CURRENT
#undef BUILDER

static os_unfair_lock gPublishLock = OS_UNFAIR_LOCK_INIT;
static bool gRegistered;  // guarded by gPublishLock

void hopi_us_rules_publish(hopi_us_rules_builder builder) {
    HopiUSRuleSet *set = builder != NULL ? CFBridgingRelease(builder) : nil;
    if (set != nil && set->_rules.count == 0) set = nil;
    bool plans = false;
    if (set != nil) {
        for (HopiUSRule *rule in set->_rules) {
            if (rule->_action == HOPI_US_ACTION_EDIT || rule->_action == HOPI_US_ACTION_DELAY) plans = true;
        }
        set->_plans = plans;
    }
    // Before the set is visible, so a planned rule finds the factories hooked.
    if (plans) hopi_us_install_factories();
    os_unfair_lock_lock(&gPublishLock);
    void *next = set != nil ? (__bridge_retained void *)set : NULL;
    os_unfair_lock_lock(&gRulesLock);
    void *old = gRuleSet;
    gRuleSet = next;
    atomic_store_explicit(&gHasRules, next != NULL, memory_order_release);
    os_unfair_lock_unlock(&gRulesLock);
    // URLSession.shared has no configuration Hopi made: the protocol reaches it registered, only
    // while rules exist, so the shared session pays nothing without them.
    if ((next != NULL) != gRegistered) {
        gRegistered = next != NULL;
        if (gRegistered) {
            [NSURLProtocol registerClass:HopiUSRuleProtocol.class];
        } else {
            [NSURLProtocol unregisterClass:HopiUSRuleProtocol.class];
        }
    }
    os_unfair_lock_unlock(&gPublishLock);
    if (old != NULL) CFRelease(old);
}

int64_t hopi_us_rule_id(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    int64_t ruleId = state->_ruleId;
    os_unfair_lock_unlock(&state->_lock);
    return ruleId;
}

NSString *hopi_us_rule_applied(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    NSString *applied = state->_ruleApplied;
    os_unfair_lock_unlock(&state->_lock);
    return applied;
}

bool hopi_us_ruled_by_protocol(hopi_us_handle handle) {
    HopiUSTask *state = STATE(handle);
    os_unfair_lock_lock(&state->_lock);
    bool by = state->_byProtocol;
    os_unfair_lock_unlock(&state->_lock);
    return by;
}

#pragma mark - Test support

void hopi_us_test_force_metadata(bool force) { atomic_store(&gForceMetadata, force); }

int64_t hopi_us_test_install_count(void) { return atomic_load(&gInstallCount); }

int64_t hopi_us_test_count_mismatches(void) { return atomic_load(&gCountMismatches); }

int64_t hopi_us_test_replays_ignored(void) { return atomic_load(&gReplaysIgnored); }

int64_t hopi_us_test_retained_high_water(void) { return atomic_load(&gRetainedHighWater); }

bool hopi_us_test_captures_mime(NSString *mime) { return !hopi_us_skips_mime(mime); }

void hopi_us_test_timing(bool on) {
    for (int i = 0; i < 3; i++) {
        atomic_store(&gHookNs[i], 0);
        atomic_store(&gHookCalls[i], 0);
    }
    atomic_store(&gMainThreadConnectionCalls, 0);
    atomic_store(&gCanInitCalls, 0);
    atomic_store(&gTiming, on);
}

int64_t hopi_us_test_match(NSURLRequest *request) {
    HopiUSRuleSet *set = hopi_us_rules();
    HopiUSView *view = hopi_us_view(request);
    if (set == nil || view == nil) return 0;
    for (HopiUSRule *rule in set->_rules) {
        if (hopi_us_rule_matches(rule, view)) return rule->_id;
    }
    return 0;
}

int64_t hopi_us_test_can_init_calls(void) { return atomic_load(&gCanInitCalls); }

bool hopi_us_test_protocol_registered(void) {
    os_unfair_lock_lock(&gPublishLock);
    bool registered = gRegistered;
    os_unfair_lock_unlock(&gPublishLock);
    return registered;
}

bool hopi_us_test_factories_installed(void) { return atomic_load(&gFactoriesInstalled); }

bool hopi_us_test_lists_protocol(NSURLSessionConfiguration *configuration) {
    return [configuration.protocolClasses containsObject:HopiUSRuleProtocol.class];
}

int64_t hopi_us_test_hook_ns(int32_t hook) { return hook >= 0 && hook < 3 ? atomic_load(&gHookNs[hook]) : 0; }

int64_t hopi_us_test_hook_calls(int32_t hook) { return hook >= 0 && hook < 3 ? atomic_load(&gHookCalls[hook]) : 0; }

int64_t hopi_us_test_main_thread_connection_calls(void) { return atomic_load(&gMainThreadConnectionCalls); }
