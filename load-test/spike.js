// Spike: ramp from 100 to 4,000 requests per second in 10 seconds, hold, then drop.
// Only 20 client keys are used, so the per-client rate limiter must shed most of the spike
// with fast 429s. Pass criteria: no server errors, and the gateway stays fast while shedding.
//
//   k6 run load-test/spike.js
import http from 'k6/http'
import { check } from 'k6'
import { BASE_URL, randomPath } from './lib.js'

const SPIKE_CLIENTS = Number(__ENV.SPIKE_CLIENTS || 20)

// 429 is the expected answer for over-limit clients, so it does not count as a failed request.
http.setResponseCallback(http.expectedStatuses(200, 429))

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 300,
      maxVUs: 2000,
      stages: [
        { target: 100, duration: '5s' },
        { target: Number(__ENV.PEAK || 4000), duration: '10s' },
        { target: Number(__ENV.PEAK || 4000), duration: '20s' },
        { target: 100, duration: '5s' },
        { target: 100, duration: '10s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{status:429}': ['p(95)<50'],
    'checks{check:no server error}': ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const res = http.get(`${BASE_URL}${randomPath()}`, {
    headers: { 'X-API-Key': `spike-client-${(__VU + __ITER) % SPIKE_CLIENTS}`, 'Cache-Control': 'no-cache' },
    tags: { name: 'products' },
  })
  check(res, {
    'no server error': (r) => r.status < 500,
    'served or rate limited': (r) => r.status === 200 || r.status === 429,
  })
}
