package com.gatepulse.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestLogTest {

    private static RequestRecord record(String path) {
        return new RequestRecord(0, 0, "id", "GET", path, "ip:1", "b1", 200, 1.0, "MISS");
    }

    @Test
    void assignsIncreasingSequenceNumbers() {
        RequestLog log = new RequestLog(8);
        log.add(record("/a"));
        log.add(record("/b"));

        assertThat(log.lastSeq()).isEqualTo(2);
        assertThat(log.since(0, 10)).extracting(RequestRecord::seq).containsExactly(1L, 2L);
        assertThat(log.since(1, 10)).extracting(RequestRecord::path).containsExactly("/b");
        assertThat(log.since(2, 10)).isEmpty();
    }

    @Test
    void keepsOnlyTheMostRecentCapacityEntries() {
        RequestLog log = new RequestLog(8);
        for (int i = 1; i <= 20; i++) {
            log.add(record("/" + i));
        }

        List<RequestRecord> all = log.since(0, 100);

        assertThat(all).hasSize(8);
        assertThat(all.get(0).path()).isEqualTo("/13");
        assertThat(all.get(7).path()).isEqualTo("/20");
    }

    @Test
    void limitReturnsTheNewestEntries() {
        RequestLog log = new RequestLog(16);
        for (int i = 1; i <= 10; i++) {
            log.add(record("/" + i));
        }
        assertThat(log.since(0, 3)).extracting(RequestRecord::path).containsExactly("/8", "/9", "/10");
        assertThat(log.latest(2)).extracting(RequestRecord::path).containsExactly("/9", "/10");
    }

    @Test
    void capacityIsRoundedUpToAPowerOfTwo() {
        assertThat(new RequestLog(1000).capacity()).isEqualTo(1024);
        assertThat(new RequestLog(1024).capacity()).isEqualTo(1024);
    }
}
