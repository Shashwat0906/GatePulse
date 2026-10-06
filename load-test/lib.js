// Shared helpers for the GatePulse k6 scripts.
import http from 'k6/http'

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '')
export const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || ''

// A pool of API keys, so the per-client rate limiter sees many clients (as in production)
// instead of one client hammering it.
export const CLIENT_KEYS = Array.from({ length: Number(__ENV.CLIENTS || 200) }, (_, i) => `k6-client-${i + 1}`)

const PATHS = ['/products', '/products/1', '/products/2', '/products/3', '/products/4', '/products/5', '/products/6']

export function randomPath() {
  return PATHS[Math.floor(Math.random() * PATHS.length)]
}

export function clientKey() {
  return CLIENT_KEYS[(__VU + __ITER) % CLIENT_KEYS.length]
}

/** POST to the gateway's admin API (chaos actions). */
export function admin(path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (ADMIN_TOKEN) headers.Authorization = `Bearer ${ADMIN_TOKEN}`
  return http.post(`${BASE_URL}${path}`, body ? JSON.stringify(body) : null, { headers, tags: { name: 'admin' } })
}
