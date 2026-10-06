package com.gatepulse.metrics;

/**
 * One finished request, as shown in the dashboard's live request log.
 *
 * @param seq       position in the log (assigned by {@link RequestLog}; 0 before that)
 * @param timestamp epoch milliseconds when the request finished
 * @param backend   backend that produced the response, or {@code null} (cache hit, 429, 503)
 * @param cache     HIT / MISS / BYPASS, or {@code null} if the request never reached the cache
 */
public record RequestRecord(long seq, long timestamp, String requestId, String method, String path, String client,
                            String backend, int status, double latencyMs, String cache) {

    RequestRecord withSeq(long newSeq) {
        return new RequestRecord(newSeq, timestamp, requestId, method, path, client, backend, status, latencyMs, cache);
    }
}
