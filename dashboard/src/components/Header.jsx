import { useEffect, useRef, useState } from 'react'
import { API_URL } from '../lib/api'
import { hostOf } from '../lib/format'
import { Button } from './ui'

const CONNECTION = {
  live: { label: 'Live', color: 'var(--good)', detail: 'Streaming every second' },
  polling: { label: 'Polling', color: 'var(--warning)', detail: 'Live stream unavailable, refreshing every 2 seconds' },
  reconnecting: { label: 'Reconnecting', color: 'var(--warning)', detail: 'Stream dropped, reconnecting' },
  connecting: { label: 'Connecting', color: 'var(--muted)', detail: 'Waiting for the gateway' },
  offline: { label: 'Offline', color: 'var(--critical)', detail: 'The gateway is not answering' },
}

export function Header({ connection, theme, onToggleTheme, token, onTokenChange, tokenRequired }) {
  const c = CONNECTION[connection] || CONNECTION.connecting
  return (
    <header className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
      <div className="flex items-center gap-3">
        <Logo />
        <div>
          <h1 className="text-[22px] font-bold leading-7 tracking-[-0.01em] text-ink">GatePulse</h1>
          <p className="text-[13px] leading-5 text-muted">Live console for the API gateway at {hostOf(API_URL)}</p>
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <span className="inline-flex h-8 items-center gap-2 rounded-md px-2 text-[13px] text-ink-2" title={c.detail} role="status">
          <span className="relative flex size-2.5">
            {connection === 'live' && (
              <span className="absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:hidden" style={{ background: c.color }} />
            )}
            <span className="relative inline-flex size-2.5 rounded-full" style={{ background: c.color }} />
          </span>
          <span className="font-medium text-ink">{c.label}</span>
          <span className="hidden text-muted md:inline">{c.detail}</span>
        </span>
        <TokenButton token={token} onChange={onTokenChange} required={tokenRequired} />
        <Button onClick={onToggleTheme} aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} theme`}>
          {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
        </Button>
      </div>
    </header>
  )
}

function TokenButton({ token, onChange, required }) {
  const [open, setOpen] = useState(false)
  const [draft, setDraft] = useState(token)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return undefined
    const close = (e) => {
      if (e.key === 'Escape' || (e.type === 'mousedown' && ref.current && !ref.current.contains(e.target))) setOpen(false)
    }
    window.addEventListener('mousedown', close)
    window.addEventListener('keydown', close)
    return () => {
      window.removeEventListener('mousedown', close)
      window.removeEventListener('keydown', close)
    }
  }, [open])

  const label = token ? 'Admin token set' : required ? 'Admin token needed' : 'Admin token'

  return (
    <div className="relative" ref={ref}>
      <Button
        onClick={() => {
          setDraft(token)
          setOpen((o) => !o)
        }}
        aria-expanded={open}
        className={required && !token ? 'border-warning' : ''}
      >
        <KeyIcon />
        {label}
      </Button>
      {open && (
        <form
          className="absolute right-0 z-40 mt-2 w-72 rounded-lg border border-line-strong bg-surface p-4 shadow-[0_10px_30px_rgb(0_0_0/0.14)]"
          onSubmit={(e) => {
            e.preventDefault()
            onChange(draft.trim())
            setOpen(false)
          }}
        >
          <label htmlFor="admin-token" className="text-[13px] font-semibold text-ink">
            Admin token
          </label>
          <p className="mt-1 text-[12px] leading-[18px] text-muted">
            {required
              ? 'This gateway requires the ADMIN_TOKEN value for changes. It is kept in this browser only.'
              : 'This gateway runs in open demo mode, so no token is needed.'}
          </p>
          <input
            id="admin-token"
            type="password"
            autoComplete="off"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            className="mt-3 h-8 w-full rounded-md border border-line-strong bg-surface px-2.5 text-[14px] text-ink outline-none focus:border-[var(--focus)]"
          />
          <div className="mt-3 flex justify-end gap-2">
            {token && (
              <Button
                onClick={() => {
                  onChange('')
                  setOpen(false)
                }}
              >
                Forget
              </Button>
            )}
            <Button tone="primary" type="submit">
              Save token
            </Button>
          </div>
        </form>
      )}
    </div>
  )
}

function Logo() {
  return (
    <svg viewBox="0 0 32 32" className="size-9" aria-hidden="true">
      <rect width="32" height="32" rx="7" fill="var(--ink)" />
      <path d="M6 17h5l3-7 4 13 3-6h5" fill="none" stroke="var(--series-1)" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function KeyIcon() {
  return (
    <svg viewBox="0 0 16 16" className="size-3.5" aria-hidden="true">
      <circle cx="5.5" cy="10.5" r="3" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="m7.7 8.3 5.8-5.8M11.5 4.5l1.8 1.8" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}

function MoonIcon() {
  return (
    <svg viewBox="0 0 16 16" className="size-4" aria-hidden="true">
      <path d="M13.5 9.6A5.6 5.6 0 0 1 6.4 2.5a5.6 5.6 0 1 0 7.1 7.1z" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  )
}

function SunIcon() {
  return (
    <svg viewBox="0 0 16 16" className="size-4" aria-hidden="true">
      <circle cx="8" cy="8" r="3" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M8 1v1.6M8 13.4V15M1 8h1.6M13.4 8H15M3 3l1.1 1.1M11.9 11.9 13 13M3 13l1.1-1.1M11.9 4.1 13 3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  )
}
