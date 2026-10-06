// Small shared building blocks. Kept plain on purpose: hairline panels, ink buttons, and
// status shown as icon + word so color never carries meaning on its own.

export function Panel({ title, description, actions, children, className = '', bodyClassName = '' }) {
  return (
    <section className={`rounded-lg border border-line bg-surface ${className}`}>
      {(title || actions) && (
        <header className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-5 pt-4">
          <div className="min-w-0">
            {title && <h2 className="text-[15px] font-semibold leading-6 text-ink">{title}</h2>}
            {description && <p className="text-[13px] leading-5 text-muted">{description}</p>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={`px-5 pb-5 pt-3 ${bodyClassName}`}>{children}</div>
    </section>
  )
}

const BUTTON_TONES = {
  primary: 'bg-ink text-surface hover:opacity-90 border-transparent',
  quiet: 'bg-surface text-ink border-line-strong hover:bg-sunk',
  danger: 'bg-surface text-critical-text border-line-strong hover:border-critical hover:bg-[var(--critical-wash)]',
}

export function Button({ tone = 'quiet', busy = false, className = '', children, disabled, ...props }) {
  return (
    <button
      type="button"
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex h-8 items-center justify-center gap-1.5 whitespace-nowrap rounded-md border px-3 text-[13px] font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${BUTTON_TONES[tone]} ${className}`}
      {...props}
    >
      {busy && <Spinner />}
      {children}
    </button>
  )
}

function Spinner() {
  return (
    <svg className="size-3.5 animate-spin motion-reduce:animate-none" viewBox="0 0 16 16" aria-hidden="true">
      <circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" strokeOpacity="0.25" strokeWidth="2" />
      <path d="M14 8a6 6 0 0 0-6-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}

/** A row of mutually exclusive options (radio semantics). */
export function Segmented({ label, options, value, onChange, disabled }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex flex-wrap rounded-md border border-line-strong bg-sunk p-0.5">
      {options.map((option) => {
        const selected = option.value === value
        return (
          <button
            key={option.value}
            type="button"
            role="radio"
            aria-checked={selected}
            disabled={disabled}
            onClick={() => !selected && onChange(option.value)}
            className={`h-7 rounded-[5px] px-2.5 text-[13px] transition-colors disabled:opacity-50 ${
              selected ? 'bg-surface font-semibold text-ink shadow-[0_0_0_1px_var(--line-strong)]' : 'text-ink-2 hover:text-ink'
            }`}
          >
            {option.label}
          </button>
        )
      })}
    </div>
  )
}

export function NumberField({ label, value, onChange, min, max, step = 1, suffix, id }) {
  return (
    <label className="flex flex-col gap-1" htmlFor={id}>
      <span className="text-[13px] text-ink-2">{label}</span>
      <span className="flex h-8 items-center rounded-md border border-line-strong bg-surface focus-within:border-[var(--focus)]">
        <input
          id={id}
          type="number"
          inputMode="decimal"
          className="tnum h-full w-full min-w-0 bg-transparent px-2.5 text-[14px] text-ink outline-none"
          value={value}
          min={min}
          max={max}
          step={step}
          onChange={(e) => onChange(e.target.value)}
        />
        {suffix && <span className="pr-2.5 text-[13px] text-muted">{suffix}</span>}
      </span>
    </label>
  )
}

export function Switch({ label, checked, onChange, disabled }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className="inline-flex items-center gap-2 text-[13px] text-ink-2 disabled:opacity-50"
    >
      <span
        className={`relative h-5 w-9 rounded-full border transition-colors ${
          checked ? 'border-transparent bg-ink' : 'border-line-strong bg-sunk'
        }`}
      >
        <span
          className={`absolute top-0.5 size-3.5 rounded-full transition-[left] ${checked ? 'left-[18px] bg-surface' : 'left-0.5 bg-muted'}`}
        />
      </span>
      {label}
    </button>
  )
}

const STATUS = {
  good: { color: 'var(--good)', text: 'text-good-text', wash: 'var(--good-wash)', icon: CheckIcon },
  warning: { color: 'var(--warning)', text: 'text-warning-text', wash: 'var(--warning-wash)', icon: HalfIcon },
  critical: { color: 'var(--critical)', text: 'text-critical-text', wash: 'var(--critical-wash)', icon: CrossIcon },
  neutral: { color: 'var(--muted)', text: 'text-ink-2', wash: 'var(--surface-sunk)', icon: DotIcon },
}

/** Status pill: icon + word on a light wash of the status color. */
export function StatusPill({ tone, children, pulseKey, title }) {
  const s = STATUS[tone] || STATUS.neutral
  const Icon = s.icon
  return (
    <span
      key={pulseKey}
      title={title}
      className={`inline-flex h-6 items-center gap-1 rounded-full px-2 text-[12px] font-semibold ${s.text} ${pulseKey ? 'circuit-pulse' : ''}`}
      style={{ background: s.wash, '--pulse-color': s.color }}
    >
      <Icon color={s.color} />
      {children}
    </span>
  )
}

function CheckIcon({ color }) {
  return (
    <svg viewBox="0 0 12 12" className="size-3" aria-hidden="true">
      <path d="M2.5 6.2 5 8.6l4.5-5" fill="none" stroke={color} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function CrossIcon({ color }) {
  return (
    <svg viewBox="0 0 12 12" className="size-3" aria-hidden="true">
      <path d="m3 3 6 6M9 3 3 9" stroke={color} strokeWidth="1.8" strokeLinecap="round" />
    </svg>
  )
}

function HalfIcon({ color }) {
  return (
    <svg viewBox="0 0 12 12" className="size-3" aria-hidden="true">
      <circle cx="6" cy="6" r="4.2" fill="none" stroke={color} strokeWidth="1.6" />
      <path d="M6 1.8a4.2 4.2 0 0 1 0 8.4z" fill={color} />
    </svg>
  )
}

function DotIcon({ color }) {
  return (
    <svg viewBox="0 0 12 12" className="size-3" aria-hidden="true">
      <circle cx="6" cy="6" r="3" fill={color} />
    </svg>
  )
}

export function Field({ label, children }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-[13px] text-ink-2">{label}</span>
      {children}
    </div>
  )
}
