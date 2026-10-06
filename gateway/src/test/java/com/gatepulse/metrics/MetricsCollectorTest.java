package com.gatepulse.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    private final AtomicLong clockMillis = new AtomicLong(1_000_000_000L); // second 1,000,000
    private final MetricsCollector metrics = new MetricsCollector(clockMillis::get, 64);

    private void record(int status, long latencyMicros, boolean cacheHit) {
        metrics.requestStarted();
        metrics.requestFinished(new RequestRecord(0, clockMillis.get(), "id", "GET", "/p", "ip:1", "b1",
                status, latencyMicros / 1000.0, cacheHit ? "HIT" : "MISS"), latencyMicros, cacheHit);
    }

    @Test
    void countsTotalsAndStatusCodes() {
        record(200, 1000, false);
        record(200, 1000, true);
        record(429, 50, false);
        record(503, 2000, false);

        assertThat(metrics.totalRequests()).isEqualTo(4);
        assertThat(metrics.totalRateLimited()).isEqualTo(1);
        assertThat(metrics.totalServerErrors()).isEqualTo(1);
        assertThat(metrics.statusCounts()).containsEntry(200, 2L).containsEntry(429, 1L).containsEntry(503, 1L);
        assertThat(metrics.inFlight()).isZero();
    }

    @Test
    void requestsPerSecondUsesCompletedSecondsOnly() {
        for (int s = 0; s < 5; s++) {
            for (int i = 0; i < 10 * (s + 1); i++) {
                record(200, 1000, false);
            }
            clockMillis.addAndGet(1000);
        }
        // Seconds held 10, 20, 30, 40, 50 requests. Now 7 more arrive in the current, unfinished second.
        for (int i = 0; i < 7; i++) {
            record(200, 1000, false);
        }

        assertThat(metrics.requestsPerSecond(5)).isEqualTo(30.0);
        assertThat(metrics.requestsPerSecond(2)).isEqualTo(45.0);
    }

    @Test
    void errorRateAndLatencyOverWindow() {
        for (int i = 0; i < 90; i++) {
            record(200, 1_000, false);
        }
        for (int i = 0; i < 10; i++) {
            record(500, 100_000, false);
        }

        assertThat(metrics.errorRate(60)).isEqualTo(0.10);
        MetricsCollector.LatencySummary latency = metrics.latency(10);
        assertThat(latency.samples()).isEqualTo(100);
        assertThat(latency.p50()).isBetween(1.0, 1.07);
        assertThat(latency.p95()).isBetween(100.0, 106.3);
    }

    @Test
    void oldSecondsAgeOutOfTheWindow() {
        record(500, 1000, false);
        clockMillis.addAndGet(61_000);
        record(200, 1000, false);

        assertThat(metrics.errorRate(60)).isZero();
        assertThat(metrics.totalServerErrors()).as("lifetime totals are kept").isEqualTo(1);
    }

    @Test
    void ringSlotsAreReusedAfterAFullLap() {
        record(200, 1000, false);
        clockMillis.addAndGet(MetricsCollector.RING_SECONDS * 1000L); // same slot, a lap later
        record(200, 1000, false);
        clockMillis.addAndGet(1000);

        List<MetricsCollector.TimelinePoint> timeline = metrics.timeline(1);
        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).requests()).as("old lap's count was not mixed in").isEqualTo(1);
    }

    @Test
    void timelineHasOnePointPerSecondIncludingQuietOnes() {
        record(200, 1000, false);
        record(429, 100, false);
        clockMillis.addAndGet(2000);
        record(200, 1000, true);
        clockMillis.addAndGet(1000);

        List<MetricsCollector.TimelinePoint> timeline = metrics.timeline(3);

        assertThat(timeline).extracting(MetricsCollector.TimelinePoint::requests).containsExactly(2L, 0L, 1L);
        assertThat(timeline.get(0).rateLimited()).isEqualTo(1);
        assertThat(timeline.get(2).cacheHits()).isEqualTo(1);
        assertThat(timeline.get(1).t() - timeline.get(0).t()).isEqualTo(1000);
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        int threads = 16;
        int perThread = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        record(i % 10 == 0 ? 503 : 200, 1000, false);
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }

        long total = (long) threads * perThread;
        assertThat(metrics.totalRequests()).isEqualTo(total);
        assertThat(metrics.totalServerErrors()).isEqualTo(total / 10);
        assertThat(metrics.latency(1).samples()).isEqualTo(total);
        assertThat(metrics.inFlight()).isZero();
    }
}
