import { useCallback, useState } from 'react'

let nextId = 1

/** Short confirmations and errors after admin actions. Announced to screen readers. */
export function useToasts() {
  const [toasts, setToasts] = useState([])

  const dismiss = useCallback((id) => setToasts((all) => all.filter((t) => t.id !== id)), [])

  const notify = useCallback(
    ({ tone, text }) => {
      const id = nextId++
      setToasts((all) => [...all.slice(-3), { id, tone, text }])
      setTimeout(() => dismiss(id), tone === 'critical' ? 7000 : 3500)
    },
    [dismiss],
  )

  return { toasts, notify, dismiss }
}

export function Toasts({ toasts, dismiss }) {
  return (
    <div aria-live="polite" className="pointer-events-none fixed inset-x-4 bottom-4 z-50 flex flex-col items-start gap-2 sm:inset-x-auto sm:left-6">
      {toasts.map((t) => (
        <div
          key={t.id}
          role={t.tone === 'critical' ? 'alert' : 'status'}
          className="pointer-events-auto flex max-w-md items-start gap-3 rounded-md border border-line-strong bg-surface px-3.5 py-2.5 text-[13px] text-ink shadow-[0_6px_24px_rgb(0_0_0/0.12)]"
        >
          <span
            aria-hidden="true"
            className="mt-1.5 size-2 shrink-0 rounded-full"
            style={{ background: t.tone === 'critical' ? 'var(--critical)' : 'var(--good)' }}
          />
          <span className="flex-1">{t.text}</span>
          <button type="button" onClick={() => dismiss(t.id)} className="text-muted hover:text-ink" aria-label="Dismiss">
            ×
          </button>
        </div>
      ))}
    </div>
  )
}
