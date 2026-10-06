# GatePulse

A production-style **mini API gateway in Java 21**. Rate limiting, load balancing, caching, health checking and circuit breaking are all **implemented from scratch** (no Resilience4j, Bucket4j, Hystrix or Guava). A live React dashboard is planned on top.

> **Status: Phase 1 of 4 complete.** Project setup, dummy backends, reverse proxy with failover, round-robin load balancing, active health checking and Docker image.
> Coming next: rate limiter, LRU cache, circuit breaker, more LB strategies, metrics, admin API (Phase 2) → dashboard (Phase 3) → load tests, deployment, full docs (Phase 4).

## What works today

| Feature | Details |
|---|---|
| Reverse proxy | Forwards any `GET/POST/PUT/PATCH/DELETE` path to a backend; strips hop-by-hop headers; adds `X-Request-Id`, `X-Gateway-Backend`, `X-Gateway-Attempts` |
| Failover | Idempotent requests that hit a dead/5xx/timed-out backend are retried on a *different* backend (up to `PROXY_MAX_ATTEMPTS`); `POST`/`PATCH` are never retried |
| Round robin | Lock-free (`AtomicLong` ticket counter), behind a `LoadBalancingStrategy` interface, swappable at runtime |
| Health checker | Probes `/health` on every backend every 5s in parallel; unhealthy after N consecutive failures, healthy after M successes |
| Dummy backends | `/products`, `/products/{id}`, `/slow`, `/health` + chaos endpoints `/admin/kill`, `/admin/revive`, `/admin/latency` |
| Two modes | `MODE=embedded` runs 3 backends inside the gateway JVM; `MODE=external` points at separate services |

## Run it

```bash
cd gateway
mvn package
java -jar target/gatepulse.jar          # embedded mode: gateway on :8080, backends on :9001-9003

curl -i localhost:8080/products         # watch X-Gateway-Backend rotate
curl -X POST localhost:9002/admin/kill  # kill backend-2: requests keep succeeding
curl localhost:8080/health              # backend-2 shows healthy=false after ~10s
curl -X POST localhost:9002/admin/revive
```

With Docker:

```bash
docker build -t gatepulse gateway
docker run -p 8080:8080 gatepulse
```

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | Gateway HTTP port |
| `MODE` | `embedded` | `embedded` or `external` |
| `BACKEND_URLS` | — | Comma-separated backend URLs (required when `MODE=external`) |
| `BACKEND_WEIGHTS` | all `1` | Comma-separated weights, same order as backends |
| `EMBEDDED_BACKEND_PORTS` | `9001,9002,9003` | Ports for embedded backends |
| `HEALTH_CHECK_INTERVAL_MS` | `5000` | Time between health-check rounds |
| `HEALTH_CHECK_TIMEOUT_MS` | `2000` | Per-probe timeout |
| `HEALTH_UNHEALTHY_THRESHOLD` | `2` | Consecutive failures before a backend is marked down |
| `HEALTH_HEALTHY_THRESHOLD` | `2` | Consecutive successes before it is marked up again |
| `BACKEND_CONNECT_TIMEOUT_MS` | `1000` | TCP connect timeout to backends |
| `BACKEND_REQUEST_TIMEOUT_MS` | `5000` | Total timeout per backend request |
| `PROXY_MAX_ATTEMPTS` | `3` | Max backends tried for one idempotent request |
| `TRUST_FORWARDED_HEADERS` | `true` | Use `X-Forwarded-For` for the client IP (enable only behind a trusted load balancer) |
| `CORS_ORIGINS` | `*` | Allowed dashboard origins, comma-separated |
| `LOG_LEVEL` | `INFO` | `DEBUG` logs one line per request |

## Project layout

```
gateway/src/main/java/com/gatepulse/
├── GatewayApplication.java   entry point, embedded backends, shutdown hook
├── GatewayServer.java        composition root: wires components, owns lifecycle
├── config/                   env-var configuration
├── core/                     Filter, FilterChain, RequestContext, GatewayResponse, GatewayHandler
├── backend/                  Backend (live state), BackendRegistry
├── loadbalancer/             LoadBalancingStrategy, RoundRobinStrategy, LoadBalancer
├── proxy/                    ProxyFilter (failover), BackendClient (JDK HttpClient)
├── health/                   HealthChecker, HealthProbe
├── dummy/                    DummyBackendServer
└── util/                     Json
```
