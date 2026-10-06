import { formatClock } from '../lib/format'
import { Panel } from './ui'

const STATE_LABEL = { CLOSED: 'closed', OPEN: 'open', HALF_OPEN: 'half-open' }
const STATE_COLOR = { CLOSED: 'var(--good)', OPEN: 'var(--critical)', HALF_OPEN: 'var(--warning)' }

/** Circuit breaker state changes, newest first: a timeline of what failed and how it recovered. */
export function CircuitEvents({ events }) {
  return (
    <Panel title="Circuit history" description="Every state change, newest first">
      {events.length === 0 ? (
        <p className="text-[13px] leading-5 text-muted">
          No circuit has changed state yet. Kill a backend while traffic is running to see it open, test and close again.
        </p>
      ) : (
        <ol className="flex max-h-[360px] flex-col overflow-auto">
          {events.map((e, i) => (
            <li key={`${e.at}-${e.backendId}-${i}`} className="grid grid-cols-[auto_1fr] gap-x-3 border-b border-line py-2 last:border-0">
              <span className="tnum pt-px text-[12px] text-muted">{formatClock(Date.parse(e.at))}</span>
              <div className="min-w-0">
                <p className="flex flex-wrap items-center gap-1.5 text-[13px] text-ink">
                  <span className="font-semibold">{e.backendId}</span>
                  <span className="text-ink-2">{STATE_LABEL[e.from]}</span>
                  <svg viewBox="0 0 16 8" className="h-2 w-4" aria-label="to">
                    <path d="M1 4h12m-3-3 3 3-3 3" fill="none" stroke="var(--muted)" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
                  </svg>
                  <span className="inline-flex items-center gap-1 font-semibold">
                    <span aria-hidden="true" className="size-2 rounded-full" style={{ background: STATE_COLOR[e.to] }} />
                    {STATE_LABEL[e.to]}
                  </span>
                </p>
                <p className="text-[12px] text-muted">{e.reason}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </Panel>
  )
}
