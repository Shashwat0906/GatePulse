package com.gatepulse.admin;

import com.gatepulse.metrics.RequestLog;
import com.gatepulse.metrics.RequestRecord;
import com.gatepulse.util.Json;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes live data to every connected dashboard over Server-Sent Events.
 *
 * <p>Once per second it builds <em>one</em> snapshot, serializes it <em>once</em>, and sends the
 * same string to every subscriber (event {@code metrics}). Each subscriber also gets the request
 * log entries that are new since its previous tick (event {@code requests}).
 *
 * <h2>Why SSE instead of WebSockets</h2>
 * Data flows one way (server to browser); commands go over normal HTTP POSTs. SSE is plain
 * HTTP, works through proxies and free-tier hosting, and the browser's {@code EventSource}
 * reconnects automatically. WebSockets would add a second protocol for no benefit here.
 *
 * <p>All sends happen on the single broadcaster thread (a new subscriber gets its first message
 * before it joins the set), so no two threads ever write to the same connection at once.
 */
public final class MetricsBroadcaster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MetricsBroadcaster.class);

    static final int INITIAL_REQUESTS = 50;
    static final int MAX_REQUESTS_PER_TICK = 100;

    private record Subscriber(SseClient client, long[] lastSeq) {
    }

    private final SnapshotService snapshots;
    private final RequestLog requestLog;
    private final long intervalMillis;
    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;

    public MetricsBroadcaster(SnapshotService snapshots, RequestLog requestLog, long intervalMillis) {
        this.snapshots = snapshots;
        this.requestLog = requestLog;
        this.intervalMillis = intervalMillis;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-broadcaster");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tickSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Called by the SSE route for each new connection. */
    public void subscribe(SseClient client) {
        client.keepAlive(); // keep the connection open after this handler returns
        // The initial burst is sent from the broadcaster thread too, to keep "one writer per connection".
        scheduler.execute(() -> {
            List<RequestRecord> recent = requestLog.latest(INITIAL_REQUESTS);
            Subscriber subscriber = new Subscriber(client, new long[]{requestLog.lastSeq()});
            if (!recent.isEmpty()) {
                subscriber.lastSeq()[0] = recent.get(recent.size() - 1).seq();
            }
            client.onClose(() -> subscribers.remove(subscriber));
            if (send(subscriber, "metrics", toJson(snapshots.snapshot())) && send(subscriber, "requests", toJson(recent))) {
                subscribers.add(subscriber);
            }
        });
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.error("event=broadcast_failed", e); // never let one bad tick cancel the schedule
        }
    }

    private void tick() {
        if (subscribers.isEmpty()) {
            return;
        }
        String snapshot = toJson(snapshots.snapshot());
        for (Subscriber subscriber : subscribers) {
            if (!send(subscriber, "metrics", snapshot)) {
                continue;
            }
            List<RequestRecord> fresh = requestLog.since(subscriber.lastSeq()[0], MAX_REQUESTS_PER_TICK);
            if (!fresh.isEmpty()) {
                subscriber.lastSeq()[0] = fresh.get(fresh.size() - 1).seq();
                send(subscriber, "requests", toJson(fresh));
            }
        }
    }

    private boolean send(Subscriber subscriber, String event, String data) {
        try {
            subscriber.client().sendEvent(event, data, null);
            if (subscriber.client().terminated()) {
                subscribers.remove(subscriber);
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            log.debug("event=sse_client_dropped error=\"{}\"", e.toString());
            subscribers.remove(subscriber);
            return false;
        }
    }

    private static String toJson(Object value) {
        return new String(Json.toBytes(value), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.client().close();
            } catch (RuntimeException ignored) {
                // closing a dead connection: nothing more to do
            }
        }
        subscribers.clear();
    }
}
