// Per-request A/B of automatic URLSession capture, without Kotlin: the same binary with capture
// started ("on") or not ("off"), against the test fixtures' loopback server. The "on" callbacks
// copy the bodies and headers as UrlSessionCapture does, on the capture queue.
//
// Build (from the repository root) and run interleaved on a booted simulator:
//   xcrun --sdk iphonesimulator clang -x objective-c -fobjc-arc -fmodules -O2 \
//     -target arm64-apple-ios15.0-simulator -Ihopi-core/src/nativeInterop/objc \
//     -Ihopi-urlsession/src/nativeInterop/objc/test -o /tmp/hopi-ab benchmark/urlsession/ab.m \
//     hopi-core/src/nativeInterop/objc/hopi_urlsession.m \
//     hopi-urlsession/src/nativeInterop/objc/test/hopi_fixtures.m -lz -framework Foundation
//   for arm in off on off on; do xcrun simctl spawn booted /tmp/hopi-ab $arm 1024 200; done
//
#import "hopi_urlsession.h"
#import "hopi_fixtures.h"
#import <time.h>
static void started(void *h) { hopi_us_release(h); }
static void finished(void *h) {
  int64_t kept = hopi_us_response_kept_bytes(h);
  void *buf = malloc(kept > 0 ? kept : 1); hopi_us_copy_response(h, buf, kept); free(buf);
  (void)hopi_us_request_headers(h); (void)hopi_us_response_headers(h); (void)hopi_us_url(h);
  hopi_us_drop_bytes(h); hopi_us_release(h);
}
static int cmp(const void *a, const void *b) { double x = *(double*)a, y = *(double*)b; return x < y ? -1 : x > y; }
int main(int argc, char **argv) { @autoreleasepool {
  bool on = argc > 1 && strcmp(argv[1], "on") == 0;
  int size = argc > 2 ? atoi(argv[2]) : 1024, n = argc > 3 ? atoi(argv[3]) : 200;
  int port = hopi_fx_server_start();
  if (on) hopi_us_start(256 * 1024, started, finished);
  NSURL *url = [NSURL URLWithString:[NSString stringWithFormat:@"http://127.0.0.1:%d/bytes?n=%d", port, size]];
  NSURLSession *s = NSURLSession.sharedSession;
  double *wall = calloc(n, sizeof(double)), *cpu = calloc(n, sizeof(double));
  for (int i = -20; i < n; i++) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    __block uint64_t end = 0;
    NSURLSessionDataTask *t = [s dataTaskWithURL:url completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) { end = clock_gettime_nsec_np(CLOCK_UPTIME_RAW); dispatch_semaphore_signal(done); }];
    uint64_t c0 = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID), w0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    [t resume];
    uint64_t c1 = clock_gettime_nsec_np(CLOCK_THREAD_CPUTIME_ID);
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    if (i >= 0) { wall[i] = (end - w0) / 1e3; cpu[i] = (c1 - c0) / 1e3; }
  }
  qsort(wall, n, sizeof(double), cmp); qsort(cpu, n, sizeof(double), cmp);
  printf("%s size=%d n=%d wall_us median=%.1f p90=%.1f resume_cpu_us median=%.2f p90=%.2f\n", on ? "on " : "off", size, n, wall[n/2], wall[n*9/10], cpu[n/2], cpu[n*9/10]);
}}
