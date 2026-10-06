// Thin client for the gateway's admin API.

export const API_URL = (import.meta.env.VITE_API_URL || 'http://localhost:8080').replace(/\/+$/, '')

export function apiUrl(path) {
  return `${API_URL}${path}`
}

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

async function parse(response) {
  const text = await response.text()
  let body
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = null
  }
  if (!response.ok) {
    const message = body?.message || `Gateway answered ${response.status}`
    throw new ApiError(response.status, message)
  }
  return body
}

/** GET a JSON document. Aborts after `timeoutMs` so a sleeping server cannot hang the UI. */
export async function getJson(path, { timeoutMs = 10000 } = {}) {
  const response = await fetch(apiUrl(path), { signal: AbortSignal.timeout(timeoutMs) })
  return parse(response)
}

/** A change through the admin API. Sends the admin token when one is set. */
export async function adminRequest(method, path, body, token) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`
  const response = await fetch(apiUrl(path), {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(15000),
  })
  return parse(response)
}
