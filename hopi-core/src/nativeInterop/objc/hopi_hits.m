// A rule's hit counter (hopi_hits.h): an Objective-C object holding one atomic, so that
// hopi-network's URLSession rules can retain it with ARC (a hook still holding an older rule set
// never reads freed memory) while Kotlin holds it through an opaque handle.

#import "hopi_hits.h"

#import <Foundation/Foundation.h>
#import <stdatomic.h>
#import <stdbool.h>

/** A rule's hit counter: the one count every source claims through (Kotlin's RuleHits on iOS). */
@interface HopiHits : NSObject {
@public
    _Atomic int64_t _count;
}
@end

@implementation HopiHits
@end

hopi_hits hopi_hits_new(void) { return (__bridge_retained void *)[HopiHits new]; }

void hopi_hits_release(hopi_hits hits) {
    if (hits != NULL) CFRelease(hits);
}

int64_t hopi_hits_value(hopi_hits hits) { return atomic_load(&((__bridge HopiHits *)hits)->_count); }

/** One compare-and-increment against times (0: unlimited), as Kotlin's RuleHits.claim. */
int32_t hopi_hits_claim(hopi_hits hits, int64_t times) {
    HopiHits *counter = (__bridge HopiHits *)hits;
    if (times <= 0) {
        atomic_fetch_add(&counter->_count, 1);
        return HOPI_HITS_CLAIM_CLAIMED;
    }
    int64_t current = atomic_load(&counter->_count);
    while (true) {
        if (current >= times) return HOPI_HITS_CLAIM_REFUSED;
        if (atomic_compare_exchange_weak(&counter->_count, &current, current + 1)) {
            return current + 1 == times ? HOPI_HITS_CLAIM_LAST : HOPI_HITS_CLAIM_CLAIMED;
        }
    }
}
