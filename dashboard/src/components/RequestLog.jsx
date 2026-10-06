import { formatClock, formatMs } from '../lib/format'
import { Button } from './ui'

function statusStyle(status) {
  if (status === 429) return { color: 'var(--serious-text)', dot: 'var(--serious)', word: 'Rate limited' }
  if (status >= 500) return { color: 'var(--critical-text)', dot: 'var(--critical)', word: 'Server error' }
  if (status >= 400) return { color: 'var(--ink-2)', dot: 'var(--muted)', word: 'Client error' }
  return { color: 'var(--good-text)', dot: 'var(--good)', word: 'OK' }
}

/** The most recent requests, newest on top. Pausing freezes the list without dropping the stream. */
export function RequestLog({ requests, paused, onTogglePause }) {
  return (
    <section aria-labelledby="log-title" className="rounded-lg border border-line bg-surface">
      <header className="flex flex-wrap items-center justify-between gap-2 px-5 pt-4">
        <div>
          <h2 id="log-title" className="text-[15px] font-semibold text-ink">
            Recent requests
          </h2>
          <p className="text-[13px] text-muted">The latest {requests.length} requests through the gateway, newest first</p>
        </div>
        <Button onClick={onTogglePause} aria-pressed={paused}>
          {paused ? 'Resume' : 'Pause'}
        </Button>
      </header>
      <div className="mt-3 max-h-[420px] overflow-auto border-t border-line">
        <table className="w-full min-w-[720px] text-left text-[13px]">
          <thead className="sticky top-0 z-10 bg-sunk text-[12px] text-ink-2">
            <tr>
              <th scope="col" className="px-5 py-2 font-medium">Time</th>
              <th scope="col" className="px-3 py-2 font-medium">Request</th>
              <th scope="col" className="px-3 py-2 font-medium">Client</th>
              <th scope="col" className="px-3 py-2 font-medium">Backend</th>
              <th scope="col" className="px-3 py-2 font-medium">Status</th>
              <th scope="col" className="px-3 py-2 text-right font-medium">Latency</th>
              <th scope="col" className="px-5 py-2 font-medium">Cache</th>
            </tr>
          </thead>
          <tbody>
            {requests.length === 0 && (
              <tr>
                <td colSpan={7} className="px-5 py-8 text-center text-muted">
                  No requests yet. Start steady traffic, or call the gateway yourself.
                </td>
              </tr>
            )}
            {requests.map((r) => {
              const s = statusStyle(r.status)
              return (
                <tr key={r.seq} className="border-t border-line">
                  <td className="tnum px-5 py-1.5 text-muted">{formatClock(r.timestamp)}</td>
                  <td className="max-w-[260px] truncate px-3 py-1.5 text-ink" title={`${r.method} ${r.path}`}>
                    <span className="text-ink-2">{r.method}</span> {r.path}
                  </td>
                  <td className="max-w-[160px] truncate px-3 py-1.5 text-ink-2" title={r.client}>
                    {r.client}
                  </td>
                  <td className="px-3 py-1.5 text-ink-2">{r.backend ?? '–'}</td>
                  <td className="px-3 py-1.5">
                    <span className="tnum inline-flex items-center gap-1.5 font-semibold" style={{ color: s.color }} title={s.word}>
                      <span aria-hidden="true" className="size-1.5 rounded-full" style={{ background: s.dot }} />
                      {r.status}
                    </span>
                  </td>
                  <td className="tnum px-3 py-1.5 text-right text-ink">{formatMs(r.latencyMs)}</td>
                  <td className="px-5 py-1.5 text-ink-2">{r.cache ? r.cache.charAt(0) + r.cache.slice(1).toLowerCase() : '–'}</td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </section>
  )
}
