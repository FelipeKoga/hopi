// A rule's hit counter (docs/design/rules.md, "iOS mirror"): the one count every source claims
// through, Kotlin's RuleHits on iOS and hopi-network's URLSession rule mirror, whose hooks run on
// app and CFNetwork threads and call no Kotlin. C declarations only, so the cinterop binding
// exposes no Objective-C class: the Kotlin side sees an opaque handle.
#ifndef HOPI_HITS_H
#define HOPI_HITS_H

#include <stdint.h>

/**
 * A rule's hit counter: an Objective-C object (core's HopiHits) behind an opaque handle.
 * hopi_hits_new returns it retained, hopi_hits_release releases it; Objective-C code may hold its
 * own strong reference through (__bridge id).
 */
typedef void *hopi_hits;

/** hopi_hits_claim's results. */
#define HOPI_HITS_CLAIM_REFUSED 0
#define HOPI_HITS_CLAIM_CLAIMED 1
// The claim used up the rule's times: the rule set must be republished without it.
#define HOPI_HITS_CLAIM_LAST 2

hopi_hits hopi_hits_new(void);
void hopi_hits_release(hopi_hits hits);
int64_t hopi_hits_value(hopi_hits hits);
/** One compare-and-increment against times (0: unlimited). */
int32_t hopi_hits_claim(hopi_hits hits, int64_t times);

#endif
