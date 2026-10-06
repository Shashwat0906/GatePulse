import { useState } from 'react'
import { formatCount, hostOf } from '../lib/format'
import { CircuitPill } from './RequestPath'
import { Button, StatusPill } from './ui'

const LATENCY_OPTIONS = [0, 200, 800, 2000]

/** One card per backend: health, circuit, load, and the chaos buttons that break it on purpose. */
export function Backends({ backends, admin }) {
  return (
    <section aria-labelledby="backends-title">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="backends-title" className="text-[15px] font-semibold text-ink">
          Backends
        </h2>
        <p className="text-[13px] text-muted">
          Kill a backend while traffic is running: requests keep succeeding, its circuit opens, and traffic shifts to the others.
        </p>
      </div>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        {backends.map((b) => (
          <BackendCard key={b.id} backend={b} admin={admin} />
        ))}
      </div>
    </section>
  )
}

function BackendCard({ backend: b, admin }) {
  const [latency, setLatency] = useState(200)
  const { run, busy } = admin
  const key = (action) => `${b.id}:${action}`
  const failureShare = b.totalRequests ? b.failedRequests / b.totalRequests : 0

  return (
    <article className={`rounded-lg border bg-surface p-4 ${b.healthy ? 'border-line' : 'border-critical'}`}>
      <header className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="text-[16px] font-semibold leading-6 text-ink">{b.id}</h3>
          <p className="truncate text-[12px] text-muted" title={b.url}>
            {hostOf(b.url)}, weight {b.weight}
          </p>
        </div>
        <StatusPill tone={b.healthy ? 'good' : 'critical'}>{b.healthy ? 'Healthy' : 'Down'}</StatusPill>
      </header>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <CircuitPill state={b.circuitState} />
        <span className="text-[12px] text-muted">
          {b.circuitState === 'OPEN'
            ? `retries in ${Math.ceil(b.circuitOpenRemainingMs / 1000)}s`
            : b.circuitState === 'CLOSED' && b.consecutiveFailures > 0
              ? `${b.consecutiveFailures} failure${b.consecutiveFailures === 1 ? '' : 's'} in a row`
              : b.circuitState === 'HALF_OPEN'
                ? 'testing with trial requests'
                : 'no recent failures'}
        </span>
      </div>

      <dl className="mt-4 grid grid-cols-3 gap-2 border-t border-line pt-3">
        <Metric label="In flight" value={b.activeConnections} />
        <Metric label="Requests" value={formatCount(b.totalRequests)} />
        <Metric label="Failed" value={formatCount(b.failedRequests)} note={b.totalRequests ? `${(failureShare * 100).toFixed(1)}%` : null} />
      </dl>

      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-line pt-3">
        {b.healthy ? (
          <Button
            tone="danger"
            busy={busy === key('kill')}
            onClick={() => run(key('kill'), 'POST', `/admin/backends/${b.id}/kill`, undefined, `${b.id} killed: it now fails every request`)}
          >
            Kill server
          </Button>
        ) : (
          <Button
            tone="primary"
            busy={busy === key('revive')}
            onClick={() => run(key('revive'), 'POST', `/admin/backends/${b.id}/revive`, undefined, `${b.id} revived`)}
          >
            Revive server
          </Button>
        )}
        <span className="flex items-center">
          <label htmlFor={`${b.id}-latency`} className="sr-only">
            Added latency for {b.id}
          </label>
          <select
            id={`${b.id}-latency`}
            value={latency}
            onChange={(e) => setLatency(Number(e.target.value))}
            className="tnum h-8 rounded-l-md border border-r-0 border-line-strong bg-surface pl-2 pr-1 text-[13px] text-ink"
          >
            {LATENCY_OPTIONS.map((ms) => (
              <option key={ms} value={ms}>
                {ms === 0 ? 'No delay' : `+${ms} ms`}
              </option>
            ))}
          </select>
          <Button
            className="rounded-l-none"
            busy={busy === key('latency')}
            onClick={() =>
              run(
                key('latency'),
                'POST',
                `/admin/backends/${b.id}/latency`,
                { ms: latency },
                latency === 0 ? `${b.id} delay removed` : `${b.id} now adds ${latency} ms to every request`,
              )
            }
          >
            Set delay
          </Button>
        </span>
        {b.circuitState !== 'CLOSED' && (
          <Button
            busy={busy === key('reset')}
            onClick={() => run(key('reset'), 'POST', `/admin/backends/${b.id}/reset-circuit`, undefined, `${b.id} circuit closed manually`)}
          >
            Close circuit
          </Button>
        )}
      </div>
    </article>
  )
}

function Metric({ label, value, note }) {
  return (
    <div>
      <dt className="text-[12px] text-muted">{label}</dt>
      <dd className="text-[18px] font-semibold leading-6 text-ink">
        {value}
        {note && <span className="ml-1 text-[12px] font-normal text-muted">{note}</span>}
      </dd>
    </div>
  )
}
