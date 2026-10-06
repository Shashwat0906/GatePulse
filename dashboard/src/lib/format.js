const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
const whole = new Intl.NumberFormat('en')

/** 1,284 / 12.9K / 4.2M */
export function formatCount(n) {
  if (n === null || n === undefined || Number.isNaN(n)) return '–'
  return Math.abs(n) < 10000 ? whole.format(Math.round(n)) : compact.format(n)
}

export function formatRate(n) {
  if (n === null || n === undefined) return '–'
  return n >= 100 ? whole.format(Math.round(n)) : n.toFixed(1)
}

/** 0.1234 -> "12.3%" */
export function formatPercent(ratio, digits = 1) {
  if (ratio === null || ratio === undefined || Number.isNaN(ratio)) return '–'
  return `${(ratio * 100).toFixed(digits)}%`
}

export function formatMs(ms) {
  if (ms === null || ms === undefined) return '–'
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} s`
  if (ms >= 100) return `${Math.round(ms)} ms`
  return `${ms.toFixed(1)} ms`
}

export function formatClock(epochMs) {
  return new Date(epochMs).toLocaleTimeString('en-GB', { hour12: false })
}

export function formatDuration(seconds) {
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  if (m < 60) return `${m}m ${seconds % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

export function hostOf(url) {
  try {
    return new URL(url).host
  } catch {
    return url
  }
}

export const STRATEGY_LABELS = {
  'round-robin': 'Round robin',
  'least-connections': 'Least connections',
  'weighted-round-robin': 'Weighted round robin',
}

export const ALGORITHM_LABELS = {
  'token-bucket': 'Token bucket',
  'sliding-window': 'Sliding window log',
}
