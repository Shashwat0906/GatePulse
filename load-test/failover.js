// Failover under load: 500 requests per second for 60 seconds, all bypassing the cache.
// At 15s backend-2 is killed; at 40s it is revived. Pass criteria: clients see no errors,
// because the gateway retries on another backend, then the circuit breaker and health checker
// take backend-2 out of rotation, and bring it back after the revive.
//
//   k6 run load-test/failover.js
import http from 'k6/http'
import { check, sleep } from 'k6'
import { BASE_URL, admin, clientKey, randomPath } from './lib.js'

export const options = {
  scenarios: {
    traffic: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 500),
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
    chaos: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: '15s',
      exec: 'chaos',
    },
  },
  thresholds: {
    'http_req_failed{name:products}': ['rate<0.001'],
    'checks{check:status is 200}': ['rate>0.999'],
    'http_req_duration{name:products}': ['p(95)<100'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const res = http.get(`${BASE_URL}${randomPath()}`, {
    headers: { 'X-API-Key': clientKey(), 'Cache-Control': 'no-cache' },
    tags: { name: 'products' },
  })
  check(res, { 'status is 200': (r) => r.status === 200 })
}

export function chaos() {
  const killed = admin('/admin/backends/backend-2/kill')
  check(killed, { 'backend-2 killed': (r) => r.status === 200 })
  sleep(25)
  const revived = admin('/admin/backends/backend-2/revive')
  check(revived, { 'backend-2 revived': (r) => r.status === 200 })
}
