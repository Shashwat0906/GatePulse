// Steady load: a constant 2,000 requests per second for 60 seconds.
// Half the requests bypass the cache (Cache-Control: no-cache) so the backend path is measured
// too, not just cache hits.
//
//   k6 run load-test/steady.js
//   k6 run -e RATE=1000 -e BASE_URL=http://localhost:8080 load-test/steady.js
import http from 'k6/http'
import { check } from 'k6'
import { BASE_URL, clientKey, randomPath } from './lib.js'

const RATE = Number(__ENV.RATE || 2000)

export const options = {
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{cache:hit}': ['p(95)<50'],
    'http_req_duration{cache:bypass}': ['p(95)<50'],
    http_req_duration: ['p(95)<50'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const bypass = Math.random() < 0.5
  const headers = { 'X-API-Key': clientKey() }
  if (bypass) headers['Cache-Control'] = 'no-cache'
  const res = http.get(`${BASE_URL}${randomPath()}`, {
    headers,
    tags: { cache: bypass ? 'bypass' : 'hit', name: 'products' },
  })
  check(res, { 'status is 200': (r) => r.status === 200 })
}
