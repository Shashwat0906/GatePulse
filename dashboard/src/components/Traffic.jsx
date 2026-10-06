import { useState } from 'react'
import { formatClock } from '../lib/format'
import { Button, NumberField, Panel, StatusPill } from './ui'

/** Built-in traffic so the console has something to show without a load-testing tool. */
export function Traffic({ traffic, admin }) {
  const { run, busy, config } = admin
  const [spikeSize, setSpikeSize] = useState(500)
  const [rps, setRps] = useState(20)
  const limits = config?.traffic ?? { maxSpikeRequests: 2000, maxSteadyRps: 200 }

  return (
    <Panel title="Traffic" description="Requests sent from the gateway to itself, through every stage">
      <div className="flex flex-col gap-5">
        <div>
          <h3 className="text-[14px] font-semibold text-ink">Steady traffic</h3>
          <p className="mb-3 text-[12px] leading-[18px] text-muted">
            A constant rate from five clients, mixing cached pages, slow calls and missing pages. Stops by itself after a few
            minutes.
          </p>
          {traffic.steadyRunning ? (
            <div className="flex flex-wrap items-center gap-3">
              <StatusPill tone="good">{traffic.steadyRps} req/s running</StatusPill>
              <span className="text-[12px] text-muted">until {formatClock(Date.parse(traffic.steadyStopsAt))}</span>
              <Button busy={busy === 'steady-stop'} onClick={() => run('steady-stop', 'DELETE', '/admin/traffic/steady', undefined, 'Steady traffic stopped')}>
                Stop
              </Button>
            </div>
          ) : (
            <div className="flex flex-wrap items-end gap-2">
              <div className="w-32">
                <NumberField id="steady-rps" label="Rate" suffix="req/s" value={rps} min={1} max={limits.maxSteadyRps} onChange={setRps} />
              </div>
              <Button
                tone="primary"
                busy={busy === 'steady'}
                onClick={() => run('steady', 'POST', '/admin/traffic/steady', { rps: Number(rps) }, `Steady traffic started at ${rps} req/s`)}
              >
                Start steady traffic
              </Button>
            </div>
          )}
        </div>

        <div className="border-t border-line pt-4">
          <h3 className="text-[14px] font-semibold text-ink">Traffic spike</h3>
          <p className="mb-3 text-[12px] leading-[18px] text-muted">
            A burst from a single client, as fast as possible. With the default limits most of it is turned away with 429.
          </p>
          <div className="flex flex-wrap items-end gap-2">
            <div className="w-32">
              <NumberField id="spike-size" label="Requests" value={spikeSize} min={1} max={limits.maxSpikeRequests} step={100} onChange={setSpikeSize} />
            </div>
            <Button
              tone="primary"
              disabled={traffic.spikeRunning}
              busy={busy === 'spike'}
              onClick={() => run('spike', 'POST', '/admin/traffic/spike', { requests: Number(spikeSize) }, `Spike of ${spikeSize} requests sent`)}
            >
              {traffic.spikeRunning ? `Sending, ${traffic.spikeRemaining} left` : `Send ${spikeSize} requests`}
            </Button>
          </div>
        </div>
      </div>
    </Panel>
  )
}
