// Test-only fixtures for the URLSession capture tests: a loopback HTTP/1.1 server, delegate
// shapes Kotlin/Native cannot write (NSProxy, a partial delegate, a subclass calling super), and
// two foreign swizzlers. Linked into the test binary only, never into a published klib.
#ifndef HOPI_FIXTURES_H
#define HOPI_FIXTURES_H

#include <stdbool.h>
#include <stdint.h>
#import <Foundation/Foundation.h>

/**
 * Starts the server on 127.0.0.1 with an OS-chosen port (once per process) and returns the port.
 * Routes (query parameters in brackets):
 *   /bytes[n, chunked, type, chunk]  n bytes of 'a'..'z' (Content-Length, or chunked in `chunk`-byte pieces)
 *   /echo                            the request body back, as text/plain
 *   /headers                         "<METHOD> <body length>", then each received header as "name: value" (sorted)
 *   /redirect[to]                    302 to `to` (default /bytes?n=10)
 *   /status[code]                    an empty response with that status
 *   /gzip[n]                         n bytes of text, sent gzip-encoded
 *   /reset[n]                        promises 2n bytes, sends n, then resets the connection
 *   /slow[n, pieces, ms]             n bytes chunked in `pieces` pieces, `ms` apart
 *   /stall[ms]                       answers after `ms` (default 5000)
 */
int32_t hopi_fx_server_start(void);

/** Requests the server has received for this path (without the query), for "nothing is re-sent". */
int64_t hopi_fx_server_requests(NSString *path);

/** The expected body of /bytes?n=N: byte i is 'a' + i % 26. */
NSData *hopi_fx_bytes(int64_t n);

/** Delegate events, in order, and the body the delegate received. */
@interface HopiFxRecorder : NSObject <NSURLSessionDataDelegate, NSURLSessionDownloadDelegate>
@property(nonatomic, readonly) NSArray<NSString *> *events;
@property(nonatomic, readonly) NSData *received;
@property(nonatomic, readonly) NSArray<NSString *> *threads;
@property(nonatomic, readonly) NSError *error;
@property(nonatomic, readonly) NSInteger completions;
/** Spins the current run loop until the task completed (main-queue delegates need it). */
- (BOOL)waitFor:(NSTimeInterval)seconds;
@end

/** Implements URLSessionTaskDelegate with no methods at all. */
@interface HopiFxPartialDelegate : NSObject <NSURLSessionTaskDelegate>
@end

/** A data delegate whose subclass overrides didReceiveData and calls super. */
@interface HopiFxBaseDelegate : HopiFxRecorder
@end

@interface HopiFxSubDelegate : HopiFxBaseDelegate
@property(nonatomic, readonly) NSInteger subCalls;
@end

/** An NSProxy forwarding to a recorder. */
@interface HopiFxProxy : NSProxy <NSURLSessionDataDelegate>
- (instancetype)initWithTarget:(HopiFxRecorder *)target;
@property(nonatomic, readonly) HopiFxRecorder *target;
@end

/** Waits for a completion handler; the handler must call `done` exactly once. */
@interface HopiFxCompletion : NSObject
@property(nonatomic, readonly) NSData *data;
@property(nonatomic, readonly) NSURLResponse *response;
@property(nonatomic, readonly) NSError *error;
@property(nonatomic, readonly) NSInteger calls;
@property(nonatomic, readonly) BOOL onMainThread;
- (void (^)(NSData *, NSURLResponse *, NSError *))dataHandler;
- (void (^)(NSURL *, NSURLResponse *, NSError *))downloadHandler;
- (BOOL)waitFor:(NSTimeInterval)seconds;
@end

/** The 25 session and task delegate selectors whose respondsToSelector: answers must not change. */
NSArray<NSString *> *hopi_fx_delegate_selectors(void);
/** respondsToSelector: of every selector above, as "1"/"0" characters. */
NSString *hopi_fx_responds(id object);

/** Installs foreign swizzlers of -resume (one exchange, one set style) and two of _didReceiveData: (set style). */
void hopi_fx_install_foreign_swizzlers(void);
/** Installs a third foreign swizzler of -resume and _didReceiveData: (set style), meant for after start(). */
void hopi_fx_install_late_swizzlers(void);
/**
 * Calls each foreign swizzler saw: [exchange resume, set resume, data passing _cmd through,
 * data passing its own selector, late resume, late data].
 */
NSArray<NSNumber *> *hopi_fx_foreign_calls(void);

/**
 * A task from the private factory Swift's async data(for:) uses (_dataTaskWithRequest:delegate:
 * completionHandler:, nil delegate), or from data(from:)'s URL one when request is nil and url is
 * given; nil when the session does not answer the selector.
 */
NSURLSessionDataTask *hopi_fx_private_data_task(NSURLSession *session, NSURLRequest *request, NSURL *url, void (^handler)(NSData *, NSURLResponse *, NSError *));

/** object_getClass's name, for "the task's class is unchanged". */
NSString *hopi_fx_class_name(id object);

/** Runs `block` on a new thread for each of `threads`, all released at once; waits for all. */
void hopi_fx_concurrently(int32_t threads, void (^block)(void));

#endif
