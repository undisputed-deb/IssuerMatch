package com.issuermatch.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * One token bucket per key, kept in memory. The map is bounded (least recently used keys are dropped) so a flood
 * of made-up addresses cannot exhaust the heap.
 * ponytail: per instance. Behind a load balancer use a shared store (Bucket4j has a Redis backend).
 */
@Component
class RateLimiter {

    static final int MAX_KEYS = 10_000;

    private final Map<String, Bucket> buckets = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    /** @return empty if the request may proceed, otherwise how many seconds to wait */
    OptionalLong tryAcquire(String key, long perMinute) {
        Bucket bucket;
        synchronized (buckets) {
            bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                    .build());
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999) / 1_000_000_000));
    }

    int size() {
        synchronized (buckets) {
            return buckets.size();
        }
    }
}
