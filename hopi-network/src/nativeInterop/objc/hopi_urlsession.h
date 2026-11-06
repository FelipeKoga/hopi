// Automatic URLSession capture (docs/design/urlsession-capture.md). C declarations only, so the
// cinterop binding exposes no Objective-C class: the Kotlin side sees opaque handles and the
// accessors below, and runs only on the library's capture queue.
#ifndef HOPI_URLSESSION_H
#define HOPI_URLSESSION_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#import <Foundation/Foundation.h>

/** A recorded task's state, retained for the duration of one callback; release with hopi_us_release. */
typedef void *hopi_us_handle;

/** Called on the capture queue only: once when a recorded task starts, once when it ends. */
typedef void (*hopi_us_callback)(hopi_us_handle handle);

/** hopi_us_start's result. */
// start already ran in this process; nothing changed.
#define HOPI_US_ALREADY_STARTED 0
// Every local task is captured with its bodies.
#define HOPI_US_BYTE_PATH 1
// CFNetwork's private connection class did not pass the self-check: metadata only.
#define HOPI_US_METADATA_PATH 2

/** Request body kinds (hopi_us_request_body_kind). */
#define HOPI_US_BODY_NONE 0
#define HOPI_US_BODY_DATA 1
#define HOPI_US_BODY_STREAM 2
#define HOPI_US_BODY_FILE 3
// An upload task created before start: its data was handed over before Hopi looked.
#define HOPI_US_BODY_NOT_OBSERVED 4

/** Response body kinds (hopi_us_response_body_kind). */
#define HOPI_US_RESPONSE_CAPTURED 0
// A download task: the file is never opened, only its size is known.
#define HOPI_US_RESPONSE_DOWNLOAD 1
// A metadata-path task: bytes did not go through the connection hooks.
#define HOPI_US_RESPONSE_NOT_OBSERVABLE 2
// A binary or streaming content type: counted, never retained.
#define HOPI_US_RESPONSE_SKIPPED 3

/**
 * Installs the hooks, once per process (later calls return HOPI_US_ALREADY_STARTED and change
 * nothing). Response bodies are kept up to max_body_bytes. The callbacks run on the capture queue.
 */
int32_t hopi_us_start(int64_t max_body_bytes, hopi_us_callback on_started, hopi_us_callback on_finished);

/** The cap for bodies captured from now on (full bodies while a host viewer holds them). */
void hopi_us_set_max_body_bytes(int64_t max_body_bytes);

/** Whether this OS passes the self-check for the byte path (side-effect free). */
bool hopi_us_self_check(void);

void hopi_us_release(hopi_us_handle handle);

int64_t hopi_us_store_id(hopi_us_handle handle);
void hopi_us_set_store_id(hopi_us_handle handle, int64_t id);

int64_t hopi_us_started_at_ms(hopi_us_handle handle);
/** Nanoseconds from the first resume to the network's end (meaningful in on_finished). */
int64_t hopi_us_duration_ns(hopi_us_handle handle);

NSString *hopi_us_method(hopi_us_handle handle);
/** originalRequest.URL: what the app asked for. */
NSString *hopi_us_url(hopi_us_handle handle);
/** currentRequest.URL at the end, or nil when it is only CFNetwork's canonical form of hopi_us_url (no redirect). */
NSString *hopi_us_final_url(hopi_us_handle handle);
/** The request's own headers as [name, value, name, value, …]. */
NSArray<NSString *> *hopi_us_request_headers(hopi_us_handle handle);

int32_t hopi_us_request_body_kind(hopi_us_handle handle);
/** Length of the whole request body (HOPI_US_BODY_DATA), of which Hopi holds at most the cap; else 0. */
int64_t hopi_us_request_body_length(hopi_us_handle handle);
/** Copies at most cap bytes of the held request body into dst; returns how many. */
int64_t hopi_us_copy_request_body(hopi_us_handle handle, void *dst, int64_t cap);
/** countOfBytesSent at the end. */
int64_t hopi_us_bytes_sent(hopi_us_handle handle);

/** HTTP status code, or -1 when the task ended without an HTTP response. */
int32_t hopi_us_status(hopi_us_handle handle);
NSArray<NSString *> *hopi_us_response_headers(hopi_us_handle handle);
int32_t hopi_us_response_body_kind(hopi_us_handle handle);
/** Response bytes received: Hopi's own count, replays excluded (countOfBytesReceived on the metadata path). */
int64_t hopi_us_response_total_bytes(hopi_us_handle handle);
/** Response bytes held, at most the cap. */
int64_t hopi_us_response_kept_bytes(hopi_us_handle handle);
/** Copies the held response regions into dst, at most cap bytes; returns how many. */
int64_t hopi_us_copy_response(hopi_us_handle handle, void *dst, int64_t cap);

/** "<domain> <code>: <description>", or nil when the task succeeded. */
NSString *hopi_us_error(hopi_us_handle handle);
bool hopi_us_cancelled(hopi_us_handle handle);
/** The metrics' networkProtocolName (e.g. "http/1.1", "h2"), or nil. */
NSString *hopi_us_protocol(hopi_us_handle handle);
/** Drops the retained request and response bytes once the terminal record is built. */
void hopi_us_drop_bytes(hopi_us_handle handle);

#pragma mark - Rules (docs/design/rules.md, "URLSession")

/** Called on the capture queue after a URLSession hook's claim used up its rule's times (core's hopi_hits_claim returned its last claim). */
typedef void (*hopi_us_exhausted_callback)(void);

/** hopi_us_rules_enable's result bits. */
// The session factory took the protocol: mock and fail reach sessions created from now on.
#define HOPI_US_RULES_SESSIONS 1

/**
 * Turns rules on, once, after hopi_us_start (later calls return the first call's result): puts the
 * rule protocol in front of every session created from now on. Nothing is matched until a rule set
 * is published. Returns 0 before hopi_us_start.
 */
int32_t hopi_us_rules_enable(hopi_us_exhausted_callback on_exhausted);

/** Rule actions (hopi_us_rules_add). */
#define HOPI_US_ACTION_DELAY 0
#define HOPI_US_ACTION_MOCK 1
#define HOPI_US_ACTION_FAIL 2
#define HOPI_US_ACTION_EDIT 3

/** Fail kinds, in FailKind's order. */
#define HOPI_US_FAIL_TIMEOUT 0
#define HOPI_US_FAIL_RESET 1
#define HOPI_US_FAIL_UNKNOWN_HOST 2
#define HOPI_US_FAIL_CANCEL 3

/** A rule set being built (retained until hopi_us_rules_publish consumes it). */
typedef void *hopi_us_rules_builder;

hopi_us_rules_builder hopi_us_rules_begin(void);
/**
 * Starts the next rule, in the order rules are tried; the calls below describe it. hits is the
 * rule's counter, a hopi_hits from core's hopi_hits_new, which the rule retains and claims through.
 */
void hopi_us_rules_add(hopi_us_rules_builder builder, int64_t rule_id, void *hits, int64_t times, NSString *applied, int32_t action);
/** methods nil: any; port -1: any; host (lowercased) and path are globs, nil: any. */
void hopi_us_rules_match(hopi_us_rules_builder builder, NSArray<NSString *> *methods, NSString *scheme, int32_t port, NSString *host, NSString *path);
/** A query (header false) or header predicate; value nil means "present", a glob otherwise. */
void hopi_us_rules_predicate(hopi_us_rules_builder builder, bool header, NSString *name, NSString *value, bool absent);
void hopi_us_rules_delay(hopi_us_rules_builder builder, int64_t before_request_ms, int64_t before_response_ms);
/** headers as [name, value, …]; Content-Length is computed here. */
void hopi_us_rules_mock(hopi_us_rules_builder builder, int32_t status, NSArray<NSString *> *headers, NSData *body);
void hopi_us_rules_fail(hopi_us_rules_builder builder, int32_t kind);
/**
 * A request edit, precomputed by the Kotlin side so the hooks only apply it: drop_query holds the
 * decoded names whose parameters go, add_query the encoded "name=value" parts appended;
 * remove_headers and keep_headers (the names the edit sets or adds) are lowercased; strip_headers
 * are the lowercased names whose original values an origin change removes (an origin-changing edit
 * is not applied on a session whose configuration adds one of them that keep_headers lacks); add_headers is
 * [name, value, …] appended after the removals. port -1 and nil fields leave the request alone.
 */
void hopi_us_rules_edit(hopi_us_rules_builder builder, NSString *method, NSString *host, int32_t port, NSString *path,
                        NSArray<NSString *> *drop_query, NSArray<NSString *> *add_query, NSArray<NSString *> *remove_headers,
                        NSArray<NSString *> *keep_headers, NSArray<NSString *> *strip_headers, NSArray<NSString *> *add_headers,
                        NSData *body);
/**
 * Publishes builder's rules, consuming it; NULL publishes none, which is the hooks' fast path. The
 * new set is live for every hook when this returns.
 */
void hopi_us_rules_publish(hopi_us_rules_builder builder);

/** The rule applied to the task (0: none), and its mark ("delay,mock"), or nil. */
int64_t hopi_us_rule_id(hopi_us_handle handle);
NSString *hopi_us_rule_applied(hopi_us_handle handle);
/** Whether the rule protocol served the response (a mock or fail): no network was used. */
bool hopi_us_ruled_by_protocol(hopi_us_handle handle);

// Test support. Counters only; nothing here changes what the hooks do, except the metadata switch.

/** Forces every task resumed from now on onto the metadata path (true) or back (false). */
void hopi_us_test_force_metadata(bool force);
/** How many times the hooks were installed (1 after any number of start calls). */
int64_t hopi_us_test_install_count(void);
/** How many byte-path tasks ended with countOfBytesReceived above Hopi's own count (bytes missed). */
int64_t hopi_us_test_count_mismatches(void);
/** How many chunk deliveries the dedupe ignored as replays. */
int64_t hopi_us_test_replays_ignored(void);
/** Largest number of response bytes retained by one task (at most the cap). */
int64_t hopi_us_test_retained_high_water(void);
/** Whether a response with this MIME type keeps its bytes (mirrors core's responseSkipReason). */
bool hopi_us_test_captures_mime(NSString *mime);

/** Resets and turns on (or off) the timing of Hopi's own part of hooks 1 to 3. */
void hopi_us_test_timing(bool on);
/** Nanoseconds Hopi spent in hook 0 (resume), 1 (data) or 2 (finish) since hopi_us_test_timing. */
int64_t hopi_us_test_hook_ns(int32_t hook);
int64_t hopi_us_test_hook_calls(int32_t hook);
/** Timed connection-hook calls (data, finish) that ran on the main thread. */
int64_t hopi_us_test_main_thread_connection_calls(void);

/** The id of the first published rule that matches request (no claim, exhaustion ignored); 0 for none. */
int64_t hopi_us_test_match(NSURLRequest *request);
/** canInitWithTask: calls since hopi_us_test_timing (counted while timing is on). */
int64_t hopi_us_test_can_init_calls(void);
/** Whether the rule protocol is registered for URLSession.shared now. */
bool hopi_us_test_protocol_registered(void);
/** Whether the task factory hooks (edit and delay) are installed. */
bool hopi_us_test_factories_installed(void);
/** Whether configuration lists the rule protocol (a session's configuration, say). */
bool hopi_us_test_lists_protocol(NSURLSessionConfiguration *configuration);

#endif
