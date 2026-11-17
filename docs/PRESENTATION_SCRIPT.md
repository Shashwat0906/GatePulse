# GatePulse presentation script

Speaking script for `GatePulse_Presentation.pptx` (the same text is in each slide's speaker notes).

| Slides | Speaker |
|---|---|
| 1-4, 8 and the conclusion on 10 | Shashwat |
| 5-7, 9 and the thank-you on 10 | Aman Kumar |

Total time: about 12-15 minutes, plus questions.

## Slide 1: Title
**Speaker: Shashwat**

Good morning everyone. I am Shashwat, roll number 230127, and this is my teammate Aman Kumar, roll number 230065. Our project is called GatePulse. GatePulse is an API gateway that we wrote from scratch in Java 21. An API gateway is a single front door that stands between users and the backend servers. It protects those servers with rate limiting, caching, load balancing, health checks and circuit breaking. It also has a live dashboard, which you can see on the right, so we can watch every request and even break servers on purpose to test it. I will explain the problem, our objectives and the technology stack. Then Aman will explain the architecture and how the system works.

## Slide 2: Introduction & Problem
**Speaker: Shashwat**

First, what is GatePulse? Think of it like the security gate of a building. Every visitor, that is every request, must pass through the gate. The gate decides if the request is allowed, if it can be answered from memory, or which server should handle it.

Why is this needed? On the right are four problems that happen when clients talk to servers directly. One: servers crash, and users see errors. Two: one client can send too many requests and slow everyone down. Three: the same data is fetched again and again, which wastes server time. Four: there is no visibility, so we cannot see what is going wrong.

Tools like Nginx or Kong already solve this, but they are black boxes. We wanted to understand how it works inside, so we wrote every algorithm ourselves.

## Slide 3: Objectives & Solution
**Speaker: Shashwat**

Our main objective was simple: keep answering clients, even when a server is failing.

This slide maps each problem to the part of GatePulse that solves it. For crashing servers, we have health checks, a circuit breaker and automatic retry on another server. For traffic floods, a token-bucket rate limiter gives each client a budget, and extra requests get the HTTP code 429, which means Too Many Requests. For repeated work, an LRU cache keeps recent answers for 5 seconds. For uneven load, the load balancer spreads requests over the servers. And for visibility, a live dashboard updates every second.

The result on the right is from a real load test: we killed one server in the middle of the test, and out of 30,003 requests, zero failed. And all of these algorithms are written by us, not taken from a gateway library.

## Slide 4: Technology Stack
**Speaker: Shashwat**

Now the technology stack, and why we chose each part.

Backend: the gateway is written in Java 21. Java 21 has virtual threads, which are very light threads, so the gateway can wait on thousands of requests at once without using much memory. We used Javalin, a small web framework on top of the Jetty server, because it has no hidden magic and we wanted to write the logic ourselves. The JDK HttpClient forwards requests, and Jackson handles JSON.

Frontend: the dashboard uses React 19 with Vite for fast builds, Tailwind CSS for styling with light and dark themes, and Recharts for the live charts.

Database: we deliberately did not use a database. Everything the gateway tracks, like counters, cache entries and circuit states, is live data that only matters for a few seconds. Keeping it in memory is much faster. If we ran several gateways together, the next step would be Redis.

Testing and DevOps: 138 automated tests with JUnit, Mockito and AssertJ; k6 for load testing; Docker and GitHub Actions to build and test on every push; and the system is hosted on Render and Vercel.

At the bottom are the APIs: any normal path is forwarded to a backend, the admin API controls the gateway, and the stream endpoint sends live data to the dashboard.

Now Aman will explain the architecture.

## Slide 5: System Architecture
**Speaker: Aman Kumar**

Thank you Shashwat. This is the architecture of GatePulse.

On the left are the clients. They can be any app, our k6 load tests, or the traffic generator built into the gateway. They send normal HTTP requests, for example GET /products.

In the middle is the gateway. Every request goes through a chain of four filters, from left to right. Metrics starts a timer. The rate limiter checks if the client is over its limit. The cache checks if we already have a fresh answer. If not, the proxy forwards the request to a backend. This design is called Chain of Responsibility: each step can answer the request itself or pass it to the next step.

The proxy uses two helpers. The load balancer picks a server, and the circuit breaker says if that server is safe to call. Below them, the health checker calls GET /health on every server every 5 seconds and tells the load balancer which servers are alive.

On the right are three backend servers that return product data. In the cloud they run inside the same program on ports 9001 to 9003; with Docker they run as separate containers.

At the bottom, the metrics collector stores the numbers, and the admin API sends them to the React dashboard every second using Server-Sent Events. The dashboard can also send commands back, like kill a server or change the algorithm.

There is no database. All state is in memory, because it is live data.

## Slide 6: Core Functionalities
**Speaker: Aman Kumar**

These are the six main modules.

One, rate limiting. Each client, identified by its API key or IP address, gets a bucket of 100 tokens. Each request uses one token, and the bucket refills at 50 tokens per second. If the bucket is empty, the client gets 429 and a Retry-After header telling it when to try again.

Two, the LRU cache. LRU means Least Recently Used: when the cache is full, the oldest unused entry is removed. It holds 500 entries for 5 seconds each, and the X-Cache header shows HIT or MISS.

Three, load balancing. Round robin takes servers in turn; least connections picks the least busy server; weighted round robin sends more traffic to stronger servers. We can switch between them live.

Four, health checks. Every 5 seconds we call each server's health endpoint. A server is marked down after 2 failed checks and up again after 2 good ones, so one small glitch does not flip it.

Five, the circuit breaker. Like the fuse in your house: after 5 failures in a row it opens and we stop calling that server for 10 seconds. Then it lets 3 test calls through. If they pass, it closes again. Any failed GET request is retried on a different server, so the user never sees the error.

Six, the live dashboard, where we can watch everything and also break things on purpose to test the system.

## Slide 7: Working & Workflow
**Speaker: Aman Kumar**

This slide shows the complete working, step by step, for one request.

Step 1: a client sends GET /products. Step 2: the metrics filter starts a timer, so we can measure how long the request takes.
Step 3: the rate limiter checks the client's bucket. If it is empty, the answer is 429 straight away.
Step 4: the cache checks if it has a fresh copy. If yes, it answers immediately with X-Cache HIT. The backend is not touched at all.
Step 5: if not, the load balancer picks a server that is healthy and whose circuit is closed.
Step 6: the proxy forwards the request. If that server fails, it tries a different server, up to 3 attempts.
Step 7: the good answer is saved in the cache, the metrics are recorded, and the client gets 200.

At the bottom is what happens when a user clicks Kill server on the dashboard. The dashboard calls the admin API, and that server starts answering 503. After 5 failures in a row, its circuit opens and the gateway stops sending it traffic for 10 seconds. The other two servers take over. Then the circuit goes half-open and allows 3 test requests. When the server is revived and the tests pass, the circuit closes and the server is back in rotation. During all of this, clients see no errors because of the retry.

Now Shashwat will show the real screens.

## Slide 8: Live Demonstration
**Speaker: Shashwat**

This is a real screenshot of our dashboard. In this moment we had killed backend-2, so you can see its card in red with the circuit open.

Number 1, at the top: live stats. Total requests, requests per second, error rate, cache hit ratio, and how many requests got 429. Notice the error rate is 0.0 percent, even with one server down.
Number 2: the request path. It shows how many requests per second each stage blocks, answers from cache, or forwards to the servers.
Number 3: two live charts. On the left, requests per second split by outcome; on the right, latency percentiles p50, p95 and p99.
Number 4: one card per backend, showing health, circuit state and request counts, with buttons to kill, revive, or add delay.
Number 5: settings to switch the load-balancing and rate-limit algorithms live, a traffic generator, and the circuit history.

A typical demo journey: open the dashboard, enter the admin token, start steady traffic, kill backend-2, watch its circuit open while the error rate stays at zero, and then revive it and watch it recover. The dashboard is live at gate-pulse-lake.vercel.app. Now Aman will talk about challenges and testing.

## Slide 9: Challenges, Testing & Future Scope
**Speaker: Aman Kumar**

On the left are the main challenges we faced.
First, thousands of requests arrive at the same time on many threads. Normal counters can lose updates, so we used lock-free atomic counters, and we wrote tests that start 16 to 32 threads together to prove the numbers stay exact.
Second, features like token refill and cache expiry depend on time, which makes tests slow and random. We used a fake clock in tests, so time moves exactly when we say.
Third, retrying must be safe. We only retry methods that are safe to repeat, like GET, and always on a different server.
Fourth, free hosting on Render sleeps after inactivity, so the dashboard shows a Waking up screen and keeps retrying until the gateway is ready.

In the middle are our testing results. We have 138 automated tests: unit, concurrency and integration tests, and they run on every push with GitHub Actions. With the k6 load-testing tool, the gateway handled 2,000 requests per second with a p95 latency of 0.8 milliseconds and zero errors. In the failover test we killed a server mid-test, and zero requests failed. In a spike up to 4,000 requests per second there were no server errors; the extra traffic got 429, which is exactly what the rate limiter should do. We also found the limit: near 5,000 requests per second the 4-CPU test machine was full.

On the right is our future scope: Redis to share limits across several gateways, adding servers live, Prometheus metrics, proper user login, and saving settings.

## Slide 10: Conclusion & Thank You
**Speakers: Shashwat (conclusion), then Aman Kumar (thank you)**

Shashwat: To conclude, GatePulse is a single front door for backend servers. It limits, caches, balances and heals traffic, so clients keep getting answers even when servers fail. We wrote every core algorithm ourselves in Java 21 and tested it with 138 automated tests. And we proved it under load: 2,000 requests per second at 0.8 milliseconds p95 latency, with zero errors during failover.

Aman: The project is live, and the code is on GitHub at the links shown. Thank you for your time. We are happy to answer your questions, or to show a live demo where we kill a server in front of you.
