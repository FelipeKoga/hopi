#import "hopi_fixtures.h"

#import <arpa/inet.h>
#import <netinet/in.h>
#import <objc/message.h>
#import <objc/runtime.h>
#import <pthread.h>
#import <stdatomic.h>
#import <sys/socket.h>
#import <unistd.h>
#import <zlib.h>

#pragma mark - Server

static NSMutableDictionary<NSString *, NSNumber *> *gRequests;
static NSLock *gRequestsLock;

NSData *hopi_fx_bytes(int64_t n) {
    NSMutableData *data = [NSMutableData dataWithLength:(NSUInteger)n];
    uint8_t *bytes = data.mutableBytes;
    for (int64_t i = 0; i < n; i++) bytes[i] = (uint8_t)('a' + i % 26);
    return data;
}

static bool hopi_fx_send(int fd, const void *bytes, size_t length) {
    const uint8_t *p = bytes;
    while (length > 0) {
        ssize_t sent = send(fd, p, length, 0);
        if (sent <= 0) return false;
        p += sent;
        length -= (size_t)sent;
    }
    return true;
}

static bool hopi_fx_send_string(int fd, NSString *text) {
    NSData *data = [text dataUsingEncoding:NSUTF8StringEncoding];
    return hopi_fx_send(fd, data.bytes, data.length);
}

static bool hopi_fx_send_chunk(int fd, NSData *data) {
    if (!hopi_fx_send_string(fd, [NSString stringWithFormat:@"%lx\r\n", (unsigned long)data.length])) return false;
    if (!hopi_fx_send(fd, data.bytes, data.length)) return false;
    return hopi_fx_send_string(fd, @"\r\n");
}

static NSData *hopi_fx_gzip(NSData *input) {
    z_stream stream = {0};
    if (deflateInit2(&stream, Z_DEFAULT_COMPRESSION, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) return nil;
    NSMutableData *out = [NSMutableData dataWithLength:deflateBound(&stream, (uLong)input.length) + 32];
    stream.next_in = (Bytef *)input.bytes;
    stream.avail_in = (uInt)input.length;
    stream.next_out = out.mutableBytes;
    stream.avail_out = (uInt)out.length;
    deflate(&stream, Z_FINISH);
    out.length = stream.total_out;
    deflateEnd(&stream);
    return out;
}

/** Reads one request: head, then a Content-Length or chunked body. Returns nil on EOF. */
static NSDictionary *hopi_fx_read_request(int fd, NSMutableData *buffer) {
    NSData *separator = [@"\r\n\r\n" dataUsingEncoding:NSUTF8StringEncoding];
    uint8_t scratch[16384];
    NSRange end;
    while ((end = [buffer rangeOfData:separator options:0 range:NSMakeRange(0, buffer.length)]).location == NSNotFound) {
        ssize_t got = recv(fd, scratch, sizeof scratch, 0);
        if (got <= 0) return nil;
        [buffer appendBytes:scratch length:(NSUInteger)got];
    }
    NSString *head = [[NSString alloc] initWithData:[buffer subdataWithRange:NSMakeRange(0, end.location)] encoding:NSUTF8StringEncoding];
    [buffer replaceBytesInRange:NSMakeRange(0, end.location + end.length) withBytes:NULL length:0];
    NSArray<NSString *> *lines = [head componentsSeparatedByString:@"\r\n"];
    NSArray<NSString *> *requestLine = [lines.firstObject componentsSeparatedByString:@" "];
    if (requestLine.count < 2) return nil;
    NSMutableDictionary<NSString *, NSString *> *headers = [NSMutableDictionary dictionary];
    for (NSString *line in [lines subarrayWithRange:NSMakeRange(1, lines.count - 1)]) {
        NSRange colon = [line rangeOfString:@":"];
        if (colon.location == NSNotFound) continue;
        NSString *name = [line substringToIndex:colon.location].lowercaseString;
        headers[name] = [[line substringFromIndex:colon.location + 1] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
    }
    NSMutableData *body = [NSMutableData data];
    if (headers[@"content-length"] != nil) {
        NSUInteger length = (NSUInteger)headers[@"content-length"].longLongValue;
        while (buffer.length < length) {
            ssize_t got = recv(fd, scratch, sizeof scratch, 0);
            if (got <= 0) return nil;
            [buffer appendBytes:scratch length:(NSUInteger)got];
        }
        [body appendData:[buffer subdataWithRange:NSMakeRange(0, length)]];
        [buffer replaceBytesInRange:NSMakeRange(0, length) withBytes:NULL length:0];
    } else if ([headers[@"transfer-encoding"].lowercaseString isEqualToString:@"chunked"]) {
        NSData *crlf = [@"\r\n" dataUsingEncoding:NSUTF8StringEncoding];
        while (true) {
            NSRange lineEnd;
            while ((lineEnd = [buffer rangeOfData:crlf options:0 range:NSMakeRange(0, buffer.length)]).location == NSNotFound) {
                ssize_t got = recv(fd, scratch, sizeof scratch, 0);
                if (got <= 0) return nil;
                [buffer appendBytes:scratch length:(NSUInteger)got];
            }
            NSString *sizeLine = [[NSString alloc] initWithData:[buffer subdataWithRange:NSMakeRange(0, lineEnd.location)] encoding:NSUTF8StringEncoding];
            unsigned long size = strtoul(sizeLine.UTF8String, NULL, 16);
            [buffer replaceBytesInRange:NSMakeRange(0, lineEnd.location + 2) withBytes:NULL length:0];
            while (buffer.length < size + 2) {
                ssize_t got = recv(fd, scratch, sizeof scratch, 0);
                if (got <= 0) return nil;
                [buffer appendBytes:scratch length:(NSUInteger)got];
            }
            [body appendData:[buffer subdataWithRange:NSMakeRange(0, size)]];
            [buffer replaceBytesInRange:NSMakeRange(0, size + 2) withBytes:NULL length:0];
            if (size == 0) break;
        }
    }
    NSURLComponents *components = [NSURLComponents componentsWithString:[@"http://localhost" stringByAppendingString:requestLine[1]]];
    NSMutableDictionary<NSString *, NSString *> *query = [NSMutableDictionary dictionary];
    for (NSURLQueryItem *item in components.queryItems) query[item.name] = item.value ?: @"";
    return @{@"method" : requestLine[0], @"path" : components.path ?: @"/", @"query" : query, @"headers" : headers, @"body" : body};
}

static void hopi_fx_respond(int fd, NSDictionary *request, bool *keepOpen) {
    NSString *path = request[@"path"];
    NSDictionary<NSString *, NSString *> *query = request[@"query"];
    NSString *method = request[@"method"];
    [gRequestsLock lock];
    gRequests[path] = @(gRequests[path].longLongValue + 1);
    [gRequestsLock unlock];
    int64_t n = query[@"n"] != nil ? query[@"n"].longLongValue : 100;
    bool head = [method isEqualToString:@"HEAD"];
    if ([path isEqualToString:@"/bytes"]) {
        NSData *body = hopi_fx_bytes(n);
        NSString *type = query[@"type"] ?: @"text/plain";
        if ([query[@"chunked"] isEqualToString:@"1"]) {
            NSUInteger piece = query[@"chunk"] != nil ? (NSUInteger)query[@"chunk"].longLongValue : 16384;
            hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: %@\r\nTransfer-Encoding: chunked\r\nX-Fixture: bytes\r\n\r\n", type]);
            for (NSUInteger offset = 0; offset < body.length && !head; offset += piece) {
                if (!hopi_fx_send_chunk(fd, [body subdataWithRange:NSMakeRange(offset, MIN(piece, body.length - offset))])) return;
            }
            if (!head) hopi_fx_send_string(fd, @"0\r\n\r\n");
        } else {
            hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: %@\r\nContent-Length: %lu\r\nX-Fixture: bytes\r\n\r\n", type, (unsigned long)body.length]);
            if (!head) hopi_fx_send(fd, body.bytes, body.length);
        }
    } else if ([path isEqualToString:@"/echo"]) {
        NSData *body = request[@"body"];
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %lu\r\n\r\n", (unsigned long)body.length]);
        hopi_fx_send(fd, body.bytes, body.length);
    } else if ([path isEqualToString:@"/headers"]) {
        // What the server received, one "name: value" line each, names lowercased and sorted,
        // after a first line "<METHOD> <body length>".
        NSDictionary<NSString *, NSString *> *headers = request[@"headers"];
        NSMutableString *text = [NSMutableString stringWithFormat:@"%@ %lu\n", method, (unsigned long)((NSData *)request[@"body"]).length];
        for (NSString *name in [headers.allKeys sortedArrayUsingSelector:@selector(compare:)]) [text appendFormat:@"%@: %@\n", name, headers[name]];
        NSData *body = [text dataUsingEncoding:NSUTF8StringEncoding];
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %lu\r\n\r\n", (unsigned long)body.length]);
        hopi_fx_send(fd, body.bytes, body.length);
    } else if ([path isEqualToString:@"/redirect"]) {
        NSString *to = query[@"to"] ?: @"/bytes?n=10";
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 302 Found\r\nLocation: %@\r\nContent-Length: 0\r\n\r\n", to]);
    } else if ([path isEqualToString:@"/status"]) {
        int code = query[@"code"] != nil ? query[@"code"].intValue : 204;
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 %d Status\r\n%@\r\n", code, code == 204 || code == 304 ? @"" : @"Content-Length: 0\r\n"]);
    } else if ([path isEqualToString:@"/gzip"]) {
        NSData *body = hopi_fx_gzip(hopi_fx_bytes(n));
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\nContent-Length: %lu\r\n\r\n", (unsigned long)body.length]);
        hopi_fx_send(fd, body.bytes, body.length);
    } else if ([path isEqualToString:@"/reset"]) {
        NSData *body = hopi_fx_bytes(n);
        hopi_fx_send_string(fd, [NSString stringWithFormat:@"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %lld\r\n\r\n", n * 2]);
        hopi_fx_send(fd, body.bytes, body.length);
        usleep(50 * 1000);
        struct linger linger = {1, 0};
        setsockopt(fd, SOL_SOCKET, SO_LINGER, &linger, sizeof linger);
        *keepOpen = false;
        return;
    } else if ([path isEqualToString:@"/slow"]) {
        int pieces = query[@"pieces"] != nil ? query[@"pieces"].intValue : 4;
        int ms = query[@"ms"] != nil ? query[@"ms"].intValue : 50;
        NSData *body = hopi_fx_bytes(n);
        hopi_fx_send_string(fd, @"HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n");
        NSUInteger piece = (body.length + (NSUInteger)pieces - 1) / (NSUInteger)pieces;
        for (NSUInteger offset = 0; offset < body.length; offset += piece) {
            if (!hopi_fx_send_chunk(fd, [body subdataWithRange:NSMakeRange(offset, MIN(piece, body.length - offset))])) return;
            usleep((useconds_t)ms * 1000);
        }
        hopi_fx_send_string(fd, @"0\r\n\r\n");
    } else if ([path isEqualToString:@"/stall"]) {
        int ms = query[@"ms"] != nil ? query[@"ms"].intValue : 5000;
        usleep((useconds_t)ms * 1000);
        hopi_fx_send_string(fd, @"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
    } else {
        hopi_fx_send_string(fd, @"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
    }
    *keepOpen = true;
}

static void *hopi_fx_connection(void *argument) {
    int fd = (int)(intptr_t)argument;
    @autoreleasepool {
        NSMutableData *buffer = [NSMutableData data];
        bool keepOpen = true;
        while (keepOpen) {
            @autoreleasepool {
                NSDictionary *request = hopi_fx_read_request(fd, buffer);
                if (request == nil) break;
                hopi_fx_respond(fd, request, &keepOpen);
            }
        }
    }
    close(fd);
    return NULL;
}

static void *hopi_fx_accept(void *argument) {
    int listener = (int)(intptr_t)argument;
    while (true) {
        int fd = accept(listener, NULL, NULL);
        if (fd < 0) continue;
        int on = 1;
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, sizeof on);
        pthread_t thread;
        pthread_create(&thread, NULL, hopi_fx_connection, (void *)(intptr_t)fd);
        pthread_detach(thread);
    }
    return NULL;
}

int32_t hopi_fx_server_start(void) {
    static int32_t port;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        gRequests = [NSMutableDictionary dictionary];
        gRequestsLock = [NSLock new];
        int listener = socket(AF_INET, SOCK_STREAM, 0);
        int on = 1;
        setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &on, sizeof on);
        struct sockaddr_in address = {0};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = 0;
        bind(listener, (struct sockaddr *)&address, sizeof address);
        listen(listener, 128);
        socklen_t length = sizeof address;
        getsockname(listener, (struct sockaddr *)&address, &length);
        port = ntohs(address.sin_port);
        pthread_t thread;
        pthread_create(&thread, NULL, hopi_fx_accept, (void *)(intptr_t)listener);
        pthread_detach(thread);
    });
    return port;
}

int64_t hopi_fx_server_requests(NSString *path) {
    [gRequestsLock lock];
    int64_t count = gRequests[path].longLongValue;
    [gRequestsLock unlock];
    return count;
}

#pragma mark - Delegates

@implementation HopiFxRecorder {
    NSMutableArray<NSString *> *_events;
    NSMutableData *_received;
    NSMutableArray<NSString *> *_threads;
    NSLock *_lock;
    dispatch_semaphore_t _done;
    NSInteger _completions;
    NSError *_error;
}

- (instancetype)init {
    if ((self = [super init])) {
        _events = [NSMutableArray array];
        _received = [NSMutableData data];
        _threads = [NSMutableArray array];
        _lock = [NSLock new];
        _done = dispatch_semaphore_create(0);
    }
    return self;
}

- (void)note:(NSString *)event {
    [_lock lock];
    [_events addObject:event];
    [_threads addObject:NSThread.isMainThread ? @"main" : @"background"];
    [_lock unlock];
}

- (NSArray<NSString *> *)events {
    [_lock lock];
    NSArray *copy = [_events copy];
    [_lock unlock];
    return copy;
}

- (NSArray<NSString *> *)threads {
    [_lock lock];
    NSArray *copy = [_threads copy];
    [_lock unlock];
    return copy;
}

- (NSData *)received {
    [_lock lock];
    NSData *copy = [_received copy];
    [_lock unlock];
    return copy;
}

- (NSError *)error {
    return _error;
}

- (NSInteger)completions {
    return _completions;
}

- (void)URLSession:(NSURLSession *)session
              dataTask:(NSURLSessionDataTask *)dataTask
    didReceiveResponse:(NSURLResponse *)response
     completionHandler:(void (^)(NSURLSessionResponseDisposition))completionHandler {
    [self note:@"response"];
    completionHandler(NSURLSessionResponseAllow);
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    [_lock lock];
    BOOL first = _received.length == 0;
    [_received appendData:data];
    [_lock unlock];
    if (first) [self note:@"data"];
}

- (void)URLSession:(NSURLSession *)session
                 downloadTask:(NSURLSessionDownloadTask *)downloadTask
    didFinishDownloadingToURL:(NSURL *)location {
    NSData *data = [NSData dataWithContentsOfURL:location];
    [_lock lock];
    [_received appendData:data ?: [NSData data]];
    [_lock unlock];
    [self note:@"downloaded"];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didFinishCollectingMetrics:(NSURLSessionTaskMetrics *)metrics {
    [self note:@"metrics"];
}

- (void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    _error = error;
    _completions++;
    [self note:@"complete"];
    dispatch_semaphore_signal(_done);
}

- (BOOL)waitFor:(NSTimeInterval)seconds {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while ([deadline timeIntervalSinceNow] > 0) {
        if (dispatch_semaphore_wait(_done, DISPATCH_TIME_NOW) == 0) return YES;
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
    return NO;
}
@end

@implementation HopiFxPartialDelegate
@end

@implementation HopiFxBaseDelegate
@end

@implementation HopiFxSubDelegate {
    NSInteger _subCalls;
}

- (NSInteger)subCalls {
    return _subCalls;
}

- (void)URLSession:(NSURLSession *)session dataTask:(NSURLSessionDataTask *)dataTask didReceiveData:(NSData *)data {
    _subCalls++;
    [super URLSession:session dataTask:dataTask didReceiveData:data];
}
@end

@implementation HopiFxProxy {
    HopiFxRecorder *_target;
}

- (instancetype)initWithTarget:(HopiFxRecorder *)target {
    _target = target;
    return self;
}

- (HopiFxRecorder *)target {
    return _target;
}

- (NSMethodSignature *)methodSignatureForSelector:(SEL)selector {
    return [_target methodSignatureForSelector:selector];
}

- (void)forwardInvocation:(NSInvocation *)invocation {
    [invocation invokeWithTarget:_target];
}

- (BOOL)respondsToSelector:(SEL)selector {
    return [_target respondsToSelector:selector];
}

- (BOOL)conformsToProtocol:(Protocol *)protocol {
    return [_target conformsToProtocol:protocol];
}
@end

@implementation HopiFxCompletion {
    dispatch_semaphore_t _done;
    NSData *_data;
    NSURLResponse *_response;
    NSError *_error;
    NSInteger _calls;
    BOOL _onMainThread;
}

- (instancetype)init {
    if ((self = [super init])) _done = dispatch_semaphore_create(0);
    return self;
}

- (NSData *)data {
    return _data;
}

- (NSURLResponse *)response {
    return _response;
}

- (NSError *)error {
    return _error;
}

- (NSInteger)calls {
    return _calls;
}

- (BOOL)onMainThread {
    return _onMainThread;
}

- (void (^)(NSData *, NSURLResponse *, NSError *))dataHandler {
    return ^(NSData *data, NSURLResponse *response, NSError *error) {
        self->_data = data;
        self->_response = response;
        self->_error = error;
        self->_calls++;
        self->_onMainThread = NSThread.isMainThread;
        dispatch_semaphore_signal(self->_done);
    };
}

- (void (^)(NSURL *, NSURLResponse *, NSError *))downloadHandler {
    return ^(NSURL *location, NSURLResponse *response, NSError *error) {
        self->_data = location != nil ? [NSData dataWithContentsOfURL:location] : nil;
        self->_response = response;
        self->_error = error;
        self->_calls++;
        self->_onMainThread = NSThread.isMainThread;
        dispatch_semaphore_signal(self->_done);
    };
}

- (BOOL)waitFor:(NSTimeInterval)seconds {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while ([deadline timeIntervalSinceNow] > 0) {
        if (dispatch_semaphore_wait(_done, DISPATCH_TIME_NOW) == 0) return YES;
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
    return NO;
}
@end

NSArray<NSString *> *hopi_fx_delegate_selectors(void) {
    return @[
        @"URLSession:didBecomeInvalidWithError:",
        @"URLSession:didReceiveChallenge:completionHandler:",
        @"URLSessionDidFinishEventsForBackgroundURLSession:",
        @"URLSession:task:willBeginDelayedRequest:completionHandler:",
        @"URLSession:taskIsWaitingForConnectivity:",
        @"URLSession:task:willPerformHTTPRedirection:newRequest:completionHandler:",
        @"URLSession:task:didReceiveChallenge:completionHandler:",
        @"URLSession:task:needNewBodyStream:",
        @"URLSession:task:needNewBodyStreamFromOffset:completionHandler:",
        @"URLSession:task:didSendBodyData:totalBytesSent:totalBytesExpectedToSend:",
        @"URLSession:task:didReceiveInformationalResponse:",
        @"URLSession:task:didFinishCollectingMetrics:",
        @"URLSession:task:didCompleteWithError:",
        @"URLSession:didCreateTask:",
        @"URLSession:dataTask:didReceiveResponse:completionHandler:",
        @"URLSession:dataTask:didBecomeDownloadTask:",
        @"URLSession:dataTask:didBecomeStreamTask:",
        @"URLSession:dataTask:didReceiveData:",
        @"URLSession:dataTask:willCacheResponse:completionHandler:",
        @"URLSession:downloadTask:didFinishDownloadingToURL:",
        @"URLSession:downloadTask:didWriteData:totalBytesWritten:totalBytesExpectedToWrite:",
        @"URLSession:downloadTask:didResumeAtOffset:expectedTotalBytes:",
        @"URLSession:readClosedForStreamTask:",
        @"URLSession:writeClosedForStreamTask:",
        @"URLSession:betterRouteDiscoveredForStreamTask:",
    ];
}

NSString *hopi_fx_responds(id object) {
    NSMutableString *out = [NSMutableString string];
    for (NSString *name in hopi_fx_delegate_selectors()) {
        [out appendString:[object respondsToSelector:NSSelectorFromString(name)] ? @"1" : @"0"];
    }
    return out;
}

NSString *hopi_fx_class_name(id object) { return NSStringFromClass(object_getClass(object)); }

NSURLSessionDataTask *hopi_fx_private_data_task(NSURLSession *session, NSURLRequest *request, NSURL *url, void (^handler)(NSData *, NSURLResponse *, NSError *)) {
    SEL selector = NSSelectorFromString(request != nil ? @"_dataTaskWithRequest:delegate:completionHandler:" : @"_dataTaskWithURL:delegate:completionHandler:");
    if (![session respondsToSelector:selector]) return nil;
    return ((id (*)(id, SEL, id, id, id))objc_msgSend)(session, selector, request != nil ? (id)request : (id)url, nil, handler);
}

#pragma mark - Foreign swizzlers

static _Atomic int64_t gForeign[6];
static IMP gLateResumePrevious;
static IMP gLateDataPrevious;
static IMP gSetResumePrevious;
static IMP gSetDataPrevious;

@interface NSURLSessionTask (HopiFxForeign)
- (void)hopiFx_exchangedResume;
@end

@implementation NSURLSessionTask (HopiFxForeign)
- (void)hopiFx_exchangedResume {
    atomic_fetch_add(&gForeign[0], 1);
    [self hopiFx_exchangedResume];
}
@end

static void hopi_fx_exchanged_data(id self, SEL _cmd, id data);
static IMP gExchangedDataOriginal;

static void hopi_fx_exchanged_data(id self, SEL _cmd, id data) {
    atomic_fetch_add(&gForeign[2], 1);
    // Passes the _cmd it was called with through, as a naive swizzler does.
    ((void (*)(id, SEL, id))gExchangedDataOriginal)(self, _cmd, data);
}

void hopi_fx_install_foreign_swizzlers(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        // Exchange style on -resume, the way AFNetworking and many SDKs do it.
        Method original = class_getInstanceMethod(NSURLSessionTask.class, @selector(resume));
        Method replacement = class_getInstanceMethod(NSURLSessionTask.class, @selector(hopiFx_exchangedResume));
        method_exchangeImplementations(original, replacement);
        // Set style on -resume, the way Firebase Performance and Pulse do it.
        Method resume = class_getInstanceMethod(NSURLSessionTask.class, @selector(resume));
        gSetResumePrevious = method_getImplementation(resume);
        method_setImplementation(resume, imp_implementationWithBlock(^(id task) {
            atomic_fetch_add(&gForeign[1], 1);
            ((void (*)(id, SEL))gSetResumePrevious)(task, @selector(resume));
        }));
        Class connection = NSClassFromString(@"__NSCFURLLocalSessionConnection");
        SEL dataSelector = NSSelectorFromString(@"_didReceiveData:");
        Method data = class_getInstanceMethod(connection, dataSelector);
        if (data != NULL) {
            gExchangedDataOriginal = method_setImplementation(data, (IMP)hopi_fx_exchanged_data);
            Method again = class_getInstanceMethod(connection, dataSelector);
            gSetDataPrevious = method_getImplementation(again);
            method_setImplementation(again, imp_implementationWithBlock(^(id self, id chunk) {
                atomic_fetch_add(&gForeign[3], 1);
                ((void (*)(id, SEL, id))gSetDataPrevious)(self, dataSelector, chunk);
            }));
        }
    });
}

void hopi_fx_install_late_swizzlers(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Method resume = class_getInstanceMethod(NSURLSessionTask.class, @selector(resume));
        gLateResumePrevious = method_getImplementation(resume);
        method_setImplementation(resume, imp_implementationWithBlock(^(id task) {
            atomic_fetch_add(&gForeign[4], 1);
            ((void (*)(id, SEL))gLateResumePrevious)(task, @selector(resume));
        }));
        Class connection = NSClassFromString(@"__NSCFURLLocalSessionConnection");
        SEL dataSelector = NSSelectorFromString(@"_didReceiveData:");
        Method data = class_getInstanceMethod(connection, dataSelector);
        if (data == NULL) return;
        gLateDataPrevious = method_getImplementation(data);
        method_setImplementation(data, imp_implementationWithBlock(^(id self, id chunk) {
            atomic_fetch_add(&gForeign[5], 1);
            ((void (*)(id, SEL, id))gLateDataPrevious)(self, dataSelector, chunk);
        }));
    });
}

NSArray<NSNumber *> *hopi_fx_foreign_calls(void) {
    NSMutableArray<NSNumber *> *out = [NSMutableArray array];
    for (int i = 0; i < 6; i++) [out addObject:@(atomic_load(&gForeign[i]))];
    return out;
}

#pragma mark - Concurrency

void hopi_fx_concurrently(int32_t threads, void (^block)(void)) {
    dispatch_group_t group = dispatch_group_create();
    dispatch_semaphore_t gate = dispatch_semaphore_create(0);
    NSMutableArray<NSThread *> *started = [NSMutableArray array];
    for (int32_t i = 0; i < threads; i++) {
        dispatch_group_enter(group);
        NSThread *thread = [[NSThread alloc] initWithBlock:^{
            dispatch_semaphore_wait(gate, DISPATCH_TIME_FOREVER);
            block();
            dispatch_group_leave(group);
        }];
        [started addObject:thread];
        [thread start];
    }
    for (int32_t i = 0; i < threads; i++) dispatch_semaphore_signal(gate);
    dispatch_group_wait(group, DISPATCH_TIME_FOREVER);
}
