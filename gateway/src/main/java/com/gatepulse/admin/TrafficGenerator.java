package com.gatepulse.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * Generates demo traffic against the gateway itself, so the dashboard has something to show
 * without running a load-testing tool.
 *
 * <ul>
 *   <li><b>Spike</b>: N requests as fast as possible (bounded concurrency), all from one client
 *       key. With default limits this deliberately trips the rate limiter, so 429s show up.</li>
 *   <li><b>Steady</b>: a constant rate spread over several client keys (so it stays under the
 *       per-client limit), with a mix of cacheable, slow and 404 paths. It stops itself after
 *       {@code maxSteadyDuration} so a forgotten browser tab cannot keep a free-tier server busy.</li>
 * </ul>
 *
 * <p>Requests go through the real front door ({@code http://127.0.0.1:<port>}), so they pass
 * every filter exactly like external traffic.
 */
public final class TrafficGenerator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);

    public static final int MAX_SPIKE_REQUESTS = 2_000;
    public static final int MAX_STEADY_RPS = 200;
    static final int SPIKE_CONCURRENCY = 50;
    static final int STEADY_CLIENTS = 5;

    /** Weighted path mix for steady traffic: mostly cacheable reads, some slow calls, some 404s. */
    private static final List<String> STEADY_PATHS = List.of(
            "/products", "/products", "/products", "/products",
            "/products/1", "/products/2", "/products/3", "/products/4", "/products/5", "/products/6",
            "/slow", "/products/404");

    /** Current activity, shown on the dashboard. */
    public record Status(boolean spikeRunning, int spikeRemaining, boolean steadyRunning, int steadyRps,
                         Instant steadyStopsAt) {
    }

    private final IntSupplier gatewayPort;
    private final Duration maxSteadyDuration;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler;
    private final HttpClient client;

    private final AtomicBoolean spikeRunning = new AtomicBoolean();
    private final AtomicLong spikeRemaining = new AtomicLong();
    private final AtomicReference<SteadyRun> steady = new AtomicReference<>();

    private record SteadyRun(int rps, Instant stopsAt, ScheduledFuture<?> ticker, ScheduledFuture<?> stopper) {
    }

    /** @param gatewayPort supplies the gateway's bound port when traffic is actually sent */
    public TrafficGenerator(IntSupplier gatewayPort, Duration maxSteadyDuration) {
        this.gatewayPort = gatewayPort;
        this.maxSteadyDuration = maxSteadyDuration;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(executor)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "traffic-generator");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts a spike in the background.
     *
     * @return false if a spike is already running
     */
    public boolean spike(int requests, String path) {
        if (requests < 1 || requests > MAX_SPIKE_REQUESTS) {
            throw new IllegalArgumentException("requests must be between 1 and " + MAX_SPIKE_REQUESTS);
        }
        String target = sanitizePath(path);
        if (!spikeRunning.compareAndSet(false, true)) {
            return false;
        }
        spikeRemaining.set(requests);
        log.info("event=traffic_spike_started requests={} path={}", requests, target);
        executor.submit(() -> runSpike(requests, target));
        return true;
    }

    private void runSpike(int requests, String path) {
        Semaphore inFlight = new Semaphore(SPIKE_CONCURRENCY);
        try {
            for (int i = 0; i < requests; i++) {
                inFlight.acquire();
                send(path, "spike-generator").whenComplete((r, e) -> {
                    inFlight.release();
                    spikeRemaining.decrementAndGet();
                });
            }
            inFlight.acquire(SPIKE_CONCURRENCY); // wait for the last batch to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            spikeRemaining.set(0);
            spikeRunning.set(false);
            log.info("event=traffic_spike_finished requests={}", requests);
        }
    }

    /** Starts (or re-rates) steady traffic. */
    public synchronized void startSteady(int rps) {
        if (rps < 1 || rps > MAX_STEADY_RPS) {
            throw new IllegalArgumentException("rps must be between 1 and " + MAX_STEADY_RPS);
        }
        stopSteady();
        long periodMicros = 1_000_000L / rps;
        AtomicLong tick = new AtomicLong();
        ScheduledFuture<?> ticker = scheduler.scheduleAtFixedRate(() -> {
            long n = tick.getAndIncrement();
            String path = STEADY_PATHS.get(ThreadLocalRandom.current().nextInt(STEADY_PATHS.size()));
            send(path, "steady-client-" + (n % STEADY_CLIENTS + 1));
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
        ScheduledFuture<?> stopper = scheduler.schedule(this::stopSteady, maxSteadyDuration.toMillis(), TimeUnit.MILLISECONDS);
        steady.set(new SteadyRun(rps, Instant.now().plus(maxSteadyDuration), ticker, stopper));
        log.info("event=steady_traffic_started rps={} autoStopSeconds={}", rps, maxSteadyDuration.toSeconds());
    }

    public synchronized void stopSteady() {
        SteadyRun run = steady.getAndSet(null);
        if (run != null) {
            run.ticker().cancel(false);
            run.stopper().cancel(false);
            log.info("event=steady_traffic_stopped");
        }
    }

    public Status status() {
        SteadyRun run = steady.get();
        return new Status(spikeRunning.get(), (int) Math.max(0, spikeRemaining.get()),
                run != null, run == null ? 0 : run.rps(), run == null ? null : run.stopsAt());
    }

    private CompletableFuture<HttpResponse<Void>> send(String path, String clientKey) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort.getAsInt() + path))
                .timeout(Duration.ofSeconds(10))
                .header("X-API-Key", clientKey)
                .GET()
                .build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    /** Only allow simple relative paths; never let the admin API turn into an open proxy to other hosts. */
    static String sanitizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/products";
        }
        String p = path.trim();
        if (!p.startsWith("/") || p.startsWith("//") || p.startsWith("/admin") || p.length() > 200
                || !p.matches("[A-Za-z0-9/_\\-.?=&%]*")) {
            throw new IllegalArgumentException("path must be a simple gateway path like /products");
        }
        return p;
    }

    @Override
    public void close() {
        stopSteady();
        scheduler.shutdownNow();
        executor.shutdownNow();
        client.close();
    }
}
