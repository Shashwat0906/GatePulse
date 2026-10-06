import { formatCount, formatPercent, formatRate } from '../lib/format'

/** The headline numbers, divided by hairlines rather than boxed into cards. */
export function StatRow({ snapshot }) {
  const s = snapshot.summary
  const stats = [
    { label: 'Total requests', value: formatCount(s.totalRequests), note: 'since the gateway started' },
    { label: 'Requests per second', value: formatRate(s.requestsPerSecond), note: 'average of the last 5 seconds' },
    {
      label: 'Error rate',
      value: formatPercent(s.errorRate),
      note: 'server errors, last 60 seconds',
      alert: s.errorRate >= 0.05,
    },
    { label: 'Cache hit ratio', value: formatPercent(s.cacheHitRatio, 0), note: `${formatCount(snapshot.cache.hits)} hits so far` },
    { label: 'Rate limited', value: formatCount(s.rateLimited), note: 'requests answered 429' },
  ]

  return (
    <dl className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line bg-line sm:grid-cols-3 lg:grid-cols-5">
      {stats.map((stat) => (
        <div key={stat.label} className="bg-surface px-5 py-4 last:col-span-2 sm:last:col-span-1">
          <dt className="text-[13px] text-ink-2">{stat.label}</dt>
          <dd className="mt-1">
            <span className={`text-[28px] font-semibold leading-9 tracking-[-0.01em] ${stat.alert ? 'text-critical-text' : 'text-ink'}`}>
              {stat.value}
            </span>
            <span className="block text-[12px] leading-4 text-muted">{stat.note}</span>
          </dd>
        </div>
      ))}
    </dl>
  )
}
