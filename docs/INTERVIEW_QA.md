# GatePulse: interview questions and answers

Questions an interviewer is likely to ask about this project, with answers that point at the
actual code. Each answer starts with the short version you can say out loud, then the detail.

---

### 1. What does this project do, in one minute?

GatePulse is an API gateway: a single entry point that sits in front of several copies of a
backend service. Every request goes through a pipeline of filters: a **rate limiter** (turns away
clients who send too much), a **cache** (answers repeated GETs from memory), a **load balancer**
(picks which backend gets the request), and a **circuit breaker** per backend (stops calling a
backend that keeps failing). A **health checker** probes every backend in the background.

I wrote all of these myself instead of using Resilience4j or Bucket4j, so I can explain how each
one works and why it's thread-safe. A React dashboard shows live traffic over Server-Sent Events
and has chaos buttons, so I can kill a backend during a demo and show that clients see no errors.

---

### 2. Walk me through what happens to one request.

`GatewayHandler` turns the Javalin request into a framework-free `RequestContext` and runs the
filter chain (`core/FilterChain`):

1. **`MetricsFilter`** starts a timer. It is the *outermost* filter, so it also sees requests
   that never reach a backend (429s, cache hits).
2. **`RateLimitFilter`** asks the `RateLimiter` for a token for this client (API key, otherwise IP).
   If there's no token, it returns `429` with `Retry-After` right away.
3. **`CacheFilter`**: for a GET, it looks up `path?query` in the LRU cache. On a HIT it returns
   immediately with `X-Cache: HIT`. On a MISS it calls the next stage and stores the 200 response.
4. **`ProxyFilter`** asks the `LoadBalancer` for an eligible backend (healthy *and* circuit not
   open), asks that backend's `CircuitBreaker` for a permit, and sends the request with the JDK
   `HttpClient`. If the attempt fails (connection error, timeout, 5xx) and the method is idempotent,
   it retries on a *different* backend.
5. On the way back out, each filter can add headers (`X-RateLimit-Remaining`, `X-Cache`), and
   `MetricsFilter` records latency, status, and a request-log entry.

---

### 3. Why Chain of Responsibility for the pipeline?

Each concern (limits, caching, proxying, metrics) is a small class with one method,
`apply(ctx, chain)`. A filter can short-circuit (429, cache hit), wrap the rest of the chain
(timing), or end it (proxy). The *order* lives in exactly one place, `GatewayServer`, so adding a
stage like auth is one new class and one line. It's the same design as servlet filters and
Spring's `HandlerInterceptor`.

Load balancing and circuit breaking are *not* separate filters, on purpose. Failover means
"pick a backend, check its breaker, call it" can run several times for one request. Keeping that
loop inside `ProxyFilter` means a retry doesn't have to re-enter the chain halfway through.

---

### 4. Token bucket vs sliding window: how do they work and when would you pick each?

**Token bucket** (`TokenBucketStrategy`): each client has a bucket of up to `capacity` tokens
that refills at `refillPerSecond`, and each request spends a token. It allows **bursts** up to
the capacity and a steady average rate. Each client needs only two numbers (tokens, last refill
time). The refill is *lazy*: tokens are computed from the elapsed time when a request arrives, so
there is no timer thread.

**Sliding window log** (`SlidingWindowLogStrategy`): it stores the timestamp of every allowed
request and allows a new one only if fewer than `limit` timestamps fall within the last window.
It is **exact**: a client can never exceed `limit` in *any* window-length span. By contrast, a
fixed-window counter can let 2× through around a window boundary (there's a test for this case).
The cost is memory: up to `limit` timestamps per client. I store them in a primitive `long`
ring buffer to avoid boxing.

I'd use token bucket for APIs where short bursts are fine (most public APIs), and sliding window
when the limit is a hard contractual or abuse cap.

---

### 5. How is the token bucket thread-safe without locks?

The bucket state is an immutable record `(tokens, lastRefillNanos)` inside an `AtomicReference`.
A request reads the state, computes the refilled amount and `tokens - 1`, and installs the new
record with `compareAndSet`. If another thread changed the bucket in between, the CAS fails and
the request retries with fresh state. Two threads can never spend the same last token. A test
has 32 threads make 6,400 attempts against a 1,000-token bucket and asserts exactly 1,000 succeed.

Rejections write nothing at all. The stored state already implies the same refill, so skipping
the CAS avoids contention during a flood of rejected requests.

---

### 6. Rate limiter memory grows with every client. How do you clean up safely?

Every 30 seconds a maintenance thread removes clients whose state is **identical to a brand-new
client**: a token bucket that has refilled to full, or a sliding window with no timestamps left.
Removing such an entry can never change a future decision.

The tricky part is a request racing with the eviction. The evictor first marks the entry as
`retired` (with a CAS, or under the window's lock), then removes it from the map. A request that
picks up a retired entry drops it and looks again, so no request is ever counted against an
entry that has already left the map. A concurrency test races the evictor against eight threads.

---

### 7. Explain the circuit breaker states and the State pattern.

- **CLOSED**: calls flow, consecutive failures are counted, and any success resets the count.
  After `failureThreshold` failures in a row it moves to OPEN.
- **OPEN**: every call is rejected instantly, without touching the backend. This fails fast and
  gives the backend room to recover. After `openDuration` the next request moves it to HALF_OPEN.
  This happens lazily, with no timer thread.
- **HALF_OPEN**: only `halfOpenTrials` trial calls get through. If all succeed it moves to
  CLOSED; if any fails it goes back to OPEN.

Each state is its own class (`ClosedState`, `OpenState`, `HalfOpenState`) implementing
`StateBehavior`, which avoids one big `switch` on an enum. A new state object is created on every
transition, so its counters automatically start at zero. Transitions are a `compareAndSet` from
the exact state object that decided to transition, so when 16 threads cross the threshold
together, exactly one transition happens and is recorded (there's a test for this).

---

### 8. What is the "permit" in your circuit breaker for?

Say a slow request starts while the circuit is CLOSED. The circuit then opens, and later moves to
HALF_OPEN, before that slow request finishes. Its failure says nothing about the trial phase, but
a naive breaker would count it as a failed trial and re-open the circuit. `tryAcquire()` returns a
`Permit` that remembers which state object issued it. The result is applied only if that state is
still current, so stale results are ignored.

---

### 9. How does failover work, and why only for some HTTP methods?

`ProxyFilter` keeps a set of backends already tried for this request. When an attempt fails, it
asks the load balancer again with those excluded, up to `PROXY_MAX_ATTEMPTS`. That is why killing
a backend during load causes zero client errors, even before the health checker notices.

Only **idempotent** methods (GET, PUT, DELETE, HEAD, OPTIONS) are retried. If a POST times out,
the backend may already have processed it, and retrying could create the order twice. Real
systems solve this with idempotency keys; I've listed that as future work.

4xx responses are returned as-is and count as *successes* for the circuit breaker. A 404 means
the backend is alive and answering; the request was bad, not the server.

---

### 10. Health checks and circuit breakers both detect failures. Why have both?

They catch different things at different speeds:

- The **circuit breaker** is *passive*: it learns from real traffic within a few requests, but
  only while traffic is flowing.
- The **health checker** is *active*: it probes `/health` every 5 seconds even with no traffic,
  so it catches a dead backend before any user hits it and notices when it comes back.

The load balancer requires both: `healthy && circuit allows traffic`. The health checker uses
**hysteresis** (N failures to mark down, M successes to mark up), so a single dropped probe
doesn't flap a backend in and out of rotation.

---

### 11. How did you implement the LRU cache, and why one lock?

A `HashMap` from key to node for O(1) lookup, plus a doubly linked list for recency, with
sentinel head and tail nodes so insert and unlink never need null checks. A hit moves the node to
the front. When the cache is over capacity, the node before the tail (least recently used) is
evicted. Each entry has an expiry time, and expired entries are removed lazily on access.

Even `get` changes the list order, so reads aren't read-only, and a lock-free LRU is genuinely
hard. A single `ReentrantLock` guards a few pointer updates (about 100ns), which is far less than
the backend call the cache saves. If it ever became a bottleneck, the next steps would be lock
striping (N independent LRU segments, giving up exact global order) or a Caffeine-style
approximate LRU with read buffers. A concurrency test runs 16 threads of random
put/get/remove, then checks that the map and the list still agree exactly.

---

### 12. How do you compute p95/p99 latency efficiently?

Storing and sorting every latency doesn't scale: at 2,000 req/s that's 120,000 numbers a minute.
`LatencyHistogram` is a log-linear histogram, the idea behind HdrHistogram. Each power of two is
split into 16 sub-buckets, so the error is at most 6.25% from 16µs to 60s, using a fixed 368
counters. Recording is one `AtomicLongArray.incrementAndGet`, and a percentile is one pass over
the counters.

`MetricsCollector` keeps a ring of 120 one-second buckets, each with its own histogram. Second
`s` lives in slot `s % 120`. The first thread to see a new second installs a fresh bucket with
CAS, so old data ages out with no cleanup thread. A sliding-window percentile merges the last N
buckets. Reported percentiles are bucket *upper bounds*, so they never understate latency.

---

### 13. Why SSE instead of WebSockets for the dashboard?

Data only flows one way, from the server to the browser. Commands go over ordinary REST calls.
SSE is plain HTTP, so it works through proxies, CDNs and free-tier hosting, and the browser's
`EventSource` reconnects automatically. WebSockets would add a second protocol and reconnect logic
for no benefit here.

The broadcaster builds **one** snapshot per second, serializes it **once**, and sends the same
string to every viewer, so the cost doesn't grow with the number of open dashboards. As a
fallback, if the stream goes silent (a proxy that buffers SSE, or a server still waking up), the
dashboard polls `/admin/metrics` every 2 seconds and keeps retrying the stream.

---

### 14. Why is everything in memory? What breaks if you run three gateway instances?

For one instance, memory is the fastest and simplest option, and it's what the project needs. With
several instances behind a load balancer:

- **Rate limits** become per-instance: a client could get N× its limit. The fix is a shared store,
  e.g. a Redis token bucket implemented as a Lua script, so check-and-decrement is atomic.
- **Caches** diverge between instances. That's acceptable for short TTLs, or you could use a shared
  cache.
- **Circuit breakers and health** are per-instance. That's usually fine, since each instance should
  protect itself.
- **Runtime config** changed through the admin API is lost on restart and not shared, so it needs
  a config store.

These are in the README under "Limitations and future work".

---

### 15. How did you test concurrency, and how do you know the numbers are real?

Each concurrency test releases many threads at once using a `CountDownLatch` "start gun", then
asserts an **exact** invariant rather than "no exception". Examples: exactly `capacity` tokens
granted, exactly one circuit transition, exactly `halfOpenTrials` permits, an exact
weighted-round-robin split across 98,000 picks, and LRU map/list consistency.

Integration tests start real backends and a real gateway on random ports and check failover,
429 headers, cache headers, admin auth, the SSE stream and the traffic generator end to end.

The load numbers in the README come from k6 runs in a GitHub Actions workflow (`load-test.yml`).
It records the runner's CPU, cores and RAM next to the results. k6 runs on the same machine as
the gateway, so the numbers are conservative, and the README says so.

---

### 16. What would you do differently, or next?

- Idempotency keys so POSTs can be retried safely.
- Distributed rate limiting with Redis, and config persistence.
- Dynamic service discovery (Consul/Kubernetes endpoints) instead of a static backend list.
- Hedged requests for tail latency (send a second copy if the first is slower than p95).
- Outlier detection that ejects a backend whose *latency* degrades, not only its error rate.
- Export metrics in Prometheus format so they can feed Grafana and alerting.
