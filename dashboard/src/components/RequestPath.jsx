import { useState } from 'react'
import { ALGORITHM_LABELS, STRATEGY_LABELS, formatRate } from '../lib/format'
import { StatusPill } from './ui'

const WINDOW_SECONDS = 5

/**
 * The gateway's filter chain drawn as the path a request actually takes, with live per-second
 * flow at each stage: how many arrive, how many the rate limiter turns away, how many the cache
 * answers, and where the load balancer sends the rest.
 */
export function RequestPath({ snapshot }) {
  const recent = snapshot.timeline.slice(-WINDOW_SECONDS)
  const sum = (key) => recent.reduce((total, point) => total + point[key], 0) / WINDOW_SECONDS
  const incoming = sum('requests')
  const rejected = sum('rateLimited')
  const cacheHits = sum('cacheHits')
  const forwarded = Math.max(0, incoming - rejected - cacheHits)
  const backendRates = useBackendRates(snapshot)

  const rl = snapshot.rateLimit
  const eligible = snapshot.backends.filter((b) => b.healthy && b.circuitState !== 'OPEN').length

  return (
    <section aria-labelledby="path-title" className="rounded-lg border border-line bg-surface px-5 py-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="path-title" className="text-[15px] font-semibold text-ink">
          Request path
        </h2>
        <p className="text-[13px] text-muted">Per-second flow through each stage, last {WINDOW_SECONDS} seconds</p>
      </div>

      <ol className="mt-5 grid grid-cols-1 items-stretch gap-y-1 lg:grid-cols-[1fr_32px_1fr_32px_1fr_32px_1fr_32px_1.5fr]">
        <Stage name="Clients" detail={`${snapshot.summary.inFlight} in flight right now`} value={formatRate(incoming)} unit="req/s in" />
        <Connector />
        <Stage
          name="Rate limiter"
          detail={rl.enabled ? `${ALGORITHM_LABELS[rl.algorithm]}, ${describeLimit(rl)}` : 'Off'}
          value={formatRate(rejected)}
          unit="req/s rejected"
          emphasis={rejected > 0 ? 'serious' : null}
        />
        <Connector />
        <Stage
          name="Cache"
          detail={snapshot.cache.enabled ? `${snapshot.cache.size} of ${snapshot.cache.capacity} entries, ${snapshot.cache.ttlMs / 1000}s TTL` : 'Off'}
          value={formatRate(cacheHits)}
          unit="req/s answered"
        />
        <Connector />
        <Stage
          name="Load balancer"
          detail={`${STRATEGY_LABELS[snapshot.loadBalancer]}, ${eligible} of ${snapshot.backends.length} eligible`}
          value={formatRate(forwarded)}
          unit="req/s forwarded"
        />
        <Connector />
        <li className="rounded-md bg-sunk px-4 py-3">
          <p className="text-[14px] font-semibold text-ink">Backends</p>
          <ul className="mt-2 flex flex-col gap-1.5">
            {snapshot.backends.map((b) => (
              <li key={b.id} className="flex items-center justify-between gap-3 text-[13px]">
                <span className="flex min-w-0 items-center gap-2">
                  <span
                    aria-hidden="true"
                    className="size-2 shrink-0 rounded-full"
                    style={{ background: b.healthy ? 'var(--good)' : 'var(--critical)' }}
                  />
                  <span className="truncate text-ink">{b.id}</span>
                  <span className="sr-only">{b.healthy ? 'healthy' : 'down'}</span>
                </span>
                <span className="flex items-center gap-2">
                  <span className="tnum text-ink-2">{formatRate(backendRates[b.id] ?? 0)}/s</span>
                  <CircuitPill state={b.circuitState} compact />
                </span>
              </li>
            ))}
          </ul>
        </li>
      </ol>
    </section>
  )
}

function describeLimit(rl) {
  return rl.algorithm === 'token-bucket'
    ? `burst ${rl.limit}, refill ${rl.refillPerSecond}/s`
    : `${rl.limit} per ${rl.windowMs / 1000}s`
}

function Stage({ name, detail, value, unit, emphasis }) {
  return (
    <li className="flex flex-col justify-between rounded-md bg-sunk px-4 py-3">
      <div>
        <p className="text-[14px] font-semibold text-ink">{name}</p>
        <p className="text-[12px] leading-[18px] text-muted">{detail}</p>
      </div>
      <p className="mt-3 flex items-baseline gap-1.5">
        <span className={`text-[26px] font-semibold leading-8 ${emphasis === 'serious' ? 'text-serious-text' : 'text-ink'}`}>{value}</span>
        <span className="text-[12px] text-ink-2">{unit}</span>
      </p>
    </li>
  )
}

function Connector() {
  return (
    <li aria-hidden="true" className="flex items-center justify-center py-0.5 lg:py-0">
      <svg viewBox="0 0 32 16" className="h-4 w-8 rotate-90 lg:rotate-0">
        <path d="M2 8h25" stroke="var(--line-strong)" strokeWidth="2" strokeLinecap="round" />
        <path d="m22 3.5 5 4.5-5 4.5" fill="none" stroke="var(--line-strong)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    </li>
  )
}

const CIRCUIT = {
  CLOSED: { tone: 'good', label: 'Closed', help: 'Circuit closed: requests flow normally' },
  HALF_OPEN: { tone: 'warning', label: 'Half-open', help: 'Circuit half-open: a few trial requests test the backend' },
  OPEN: { tone: 'critical', label: 'Open', help: 'Circuit open: the gateway stops sending traffic here for a while' },
}

/** Circuit state as icon + word. Pulses once when the state changes (not on first render). */
export function CircuitPill({ state, compact = false }) {
  const [seen, setSeen] = useState({ state, changes: 0 })
  if (seen.state !== state) {
    setSeen({ state, changes: seen.changes + 1 })
  }
  const c = CIRCUIT[state] || CIRCUIT.CLOSED
  return (
    <StatusPill tone={c.tone} pulseKey={seen.changes > 0 ? `${state}-${seen.changes}` : undefined} title={c.help}>
      {compact ? c.label : `Circuit ${c.label.toLowerCase()}`}
    </StatusPill>
  )
}

/** Requests per second per backend, from the change in lifetime totals between snapshots. */
function useBackendRates(snapshot) {
  const [track, setTrack] = useState({ snapshot, rates: {} })
  if (track.snapshot !== snapshot) {
    const prev = track.snapshot
    const seconds = (snapshot.timestamp - prev.timestamp) / 1000
    const rates = {}
    for (const b of snapshot.backends) {
      const before = prev.backends.find((p) => p.id === b.id)
      const instant = before && seconds > 0 ? Math.max(0, (b.totalRequests - before.totalRequests) / seconds) : 0
      const old = track.rates[b.id]
      // Light smoothing so the number is readable rather than jittery.
      rates[b.id] = old === undefined ? instant : old * 0.5 + instant * 0.5
    }
    setTrack({ snapshot, rates })
  }
  return track.rates
}
