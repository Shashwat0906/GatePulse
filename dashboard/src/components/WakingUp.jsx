import { useEffect, useState } from 'react'
import { API_URL } from '../lib/api'

/**
 * Shown until the first data arrives. Free hosting tiers put idle services to sleep, and the
 * first request can take 30-60 seconds while the container starts, so this explains the wait
 * instead of looking broken.
 */
export function WakingUp({ startedAt, connection }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(t)
  }, [])
  const seconds = Math.max(0, Math.round((now - startedAt) / 1000))
  const slow = seconds > 75

  return (
    <div className="mx-auto mt-[12vh] max-w-lg px-4 text-center">
      <svg viewBox="0 0 120 24" className="mx-auto h-6 w-32" aria-hidden="true">
        <path d="M2 12h28l6-9 8 18 6-12 4 3h64" fill="none" stroke="var(--line-strong)" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        <path
          d="M2 12h28l6-9 8 18 6-12 4 3h64"
          fill="none"
          stroke="var(--series-1)"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="flowing"
        />
      </svg>
      <h2 className="mt-6 text-[22px] font-semibold text-ink">Waking up the gateway…</h2>
      <p className="mt-2 text-[15px] leading-6 text-ink-2">
        The gateway runs on a free hosting tier that sleeps when idle. The first request starts it again, which usually
        takes 30 to 60 seconds. This page connects on its own as soon as it answers.
      </p>
      <p className="tnum mt-4 text-[13px] text-muted" role="status">
        Waiting {seconds}s for {API_URL}
        {connection === 'offline' ? ' (not reachable yet)' : ''}
      </p>
      {slow && (
        <p className="mt-4 rounded-md border border-line bg-surface px-4 py-3 text-left text-[13px] leading-5 text-ink-2">
          Still nothing after {seconds} seconds. Check that the gateway is deployed and that the dashboard's{' '}
          <code className="text-ink">VITE_API_URL</code> points at it, and that the gateway allows this site in{' '}
          <code className="text-ink">CORS_ORIGINS</code>. Its <code className="text-ink">/health</code> page should open in
          a browser tab.
        </p>
      )}
    </div>
  )
}
