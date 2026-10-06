import { useState } from 'react'
import { ALGORITHM_LABELS, STRATEGY_LABELS } from '../lib/format'
import { Button, NumberField, Panel, Segmented, Switch } from './ui'

/** Runtime settings. Every change goes through the admin API and applies without a restart. */
export function Controls({ admin }) {
  const { config } = admin
  if (!config) {
    return (
      <Panel title="Settings">
        <p className="text-[13px] text-muted">Loading the current settings…</p>
      </Panel>
    )
  }
  return (
    <Panel title="Settings" description="Changes apply immediately, no restart">
      <div className="flex flex-col divide-y divide-line">
        <LoadBalancerSettings admin={admin} />
        <RateLimitSettings admin={admin} />
        <CacheSettings admin={admin} />
        <CircuitSettings admin={admin} />
      </div>
    </Panel>
  )
}

function Group({ title, help, children }) {
  return (
    <div className="py-4 first:pt-1 last:pb-0">
      <h3 className="text-[14px] font-semibold text-ink">{title}</h3>
      {help && <p className="mb-3 text-[12px] leading-[18px] text-muted">{help}</p>}
      {children}
    </div>
  )
}

function LoadBalancerSettings({ admin }) {
  const { config, run, busy } = admin
  const lb = config.loadBalancer
  return (
    <Group title="Load balancing" help="How the gateway picks a backend among the healthy ones.">
      <Segmented
        label="Load balancing strategy"
        value={lb.strategy}
        disabled={busy === 'lb'}
        options={lb.strategies.map((s) => ({ value: s, label: STRATEGY_LABELS[s] || s }))}
        onChange={(strategy) =>
          run('lb', 'PUT', '/admin/config/load-balancer', { strategy }, `Load balancing set to ${STRATEGY_LABELS[strategy].toLowerCase()}`)
        }
      />
    </Group>
  )
}

/** Local draft of a settings group, reset whenever the server's values change. */
function useDraft(source) {
  const sourceKey = JSON.stringify(source)
  const [state, setState] = useState({ sourceKey, draft: source })
  // Adjusting state while rendering (React's recommended alternative to a syncing effect).
  if (state.sourceKey !== sourceKey) {
    setState({ sourceKey, draft: source })
  }
  const set = (key) => (value) => setState((s) => ({ ...s, draft: { ...s.draft, [key]: value } }))
  const dirty = JSON.stringify(state.draft) !== sourceKey
  return [state.draft, set, dirty]
}

function RateLimitSettings({ admin }) {
  const { config, run, busy } = admin
  const rl = config.rateLimit
  const [draft, set, dirty] = useDraft({
    enabled: rl.enabled,
    algorithm: rl.algorithm,
    limit: rl.limit,
    refillPerSecond: rl.refillPerSecond,
    windowMs: rl.windowMs,
  })
  const tokenBucket = draft.algorithm === 'token-bucket'

  const apply = () =>
    run(
      'rate',
      'PUT',
      '/admin/config/rate-limit',
      {
        enabled: draft.enabled,
        algorithm: draft.algorithm,
        limit: Number(draft.limit),
        refillPerSecond: Number(draft.refillPerSecond),
        windowMs: Number(draft.windowMs),
      },
      'Rate limit updated',
    )

  return (
    <Group title="Rate limiting" help="Per client: the X-API-Key header if sent, otherwise the IP address.">
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <Switch label={draft.enabled ? 'On' : 'Off'} checked={draft.enabled} onChange={set('enabled')} />
          <Segmented
            label="Rate limit algorithm"
            value={draft.algorithm}
            options={rl.algorithms.map((a) => ({ value: a, label: ALGORITHM_LABELS[a] || a }))}
            onChange={set('algorithm')}
          />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <NumberField
            id="rl-limit"
            label={tokenBucket ? 'Burst size' : 'Requests per window'}
            value={draft.limit}
            min={1}
            onChange={set('limit')}
          />
          {tokenBucket ? (
            <NumberField id="rl-refill" label="Refill rate" suffix="/s" value={draft.refillPerSecond} min={0.1} step={0.5} onChange={set('refillPerSecond')} />
          ) : (
            <NumberField id="rl-window" label="Window" suffix="ms" value={draft.windowMs} min={1} step={100} onChange={set('windowMs')} />
          )}
        </div>
        <ApplyRow dirty={dirty} busy={busy === 'rate'} onApply={apply} />
      </div>
    </Group>
  )
}

function CacheSettings({ admin }) {
  const { config, run, busy } = admin
  const c = config.cache
  const [draft, set, dirty] = useDraft({ enabled: c.enabled, ttlMs: c.ttlMs, capacity: c.capacity })

  return (
    <Group title="Response cache" help="Successful GET responses are kept in memory and reused until they expire.">
      <div className="flex flex-col gap-3">
        <Switch label={draft.enabled ? 'On' : 'Off'} checked={draft.enabled} onChange={set('enabled')} />
        <div className="grid grid-cols-2 gap-3">
          <NumberField id="cache-ttl" label="Time to live" suffix="ms" value={draft.ttlMs} min={1} step={500} onChange={set('ttlMs')} />
          <NumberField id="cache-capacity" label="Capacity" suffix="entries" value={draft.capacity} min={1} onChange={set('capacity')} />
        </div>
        <ApplyRow
          dirty={dirty}
          busy={busy === 'cache'}
          onApply={() =>
            run('cache', 'PUT', '/admin/config/cache', { enabled: draft.enabled, ttlMs: Number(draft.ttlMs), capacity: Number(draft.capacity) }, 'Cache settings updated')
          }
          extra={
            <Button busy={busy === 'cache-clear'} onClick={() => run('cache-clear', 'DELETE', '/admin/cache', undefined, 'Cache emptied')}>
              Empty cache
            </Button>
          }
        />
      </div>
    </Group>
  )
}

function CircuitSettings({ admin }) {
  const { config, run, busy } = admin
  const cb = config.circuitBreaker
  const [draft, set, dirty] = useDraft({
    failureThreshold: cb.failureThreshold,
    openDurationMs: cb.openDurationMs,
    halfOpenTrials: cb.halfOpenTrials,
  })

  return (
    <Group title="Circuit breaker" help="Opens after repeated failures, waits, then lets a few trial requests through.">
      <div className="flex flex-col gap-3">
        <div className="grid grid-cols-3 gap-3">
          <NumberField id="cb-threshold" label="Failures to open" value={draft.failureThreshold} min={1} onChange={set('failureThreshold')} />
          <NumberField id="cb-open" label="Open for" suffix="ms" value={draft.openDurationMs} min={1} step={1000} onChange={set('openDurationMs')} />
          <NumberField id="cb-trials" label="Trial requests" value={draft.halfOpenTrials} min={1} onChange={set('halfOpenTrials')} />
        </div>
        <ApplyRow
          dirty={dirty}
          busy={busy === 'cb'}
          onApply={() =>
            run(
              'cb',
              'PUT',
              '/admin/config/circuit-breaker',
              {
                failureThreshold: Number(draft.failureThreshold),
                openDurationMs: Number(draft.openDurationMs),
                halfOpenTrials: Number(draft.halfOpenTrials),
              },
              'Circuit breaker updated',
            )
          }
        />
      </div>
    </Group>
  )
}

function ApplyRow({ dirty, busy, onApply, extra }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button tone="primary" disabled={!dirty} busy={busy} onClick={onApply}>
        Apply
      </Button>
      {extra}
      {dirty && <span className="text-[12px] text-muted">Unsaved changes</span>}
    </div>
  )
}
