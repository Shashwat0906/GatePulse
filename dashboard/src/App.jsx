import { Backends } from './components/Backends'
import { CircuitEvents } from './components/CircuitEvents'
import { Controls } from './components/Controls'
import { Header } from './components/Header'
import { RequestLog } from './components/RequestLog'
import { RequestPath } from './components/RequestPath'
import { StatRow } from './components/StatRow'
import { Toasts, useToasts } from './components/Toasts'
import { Traffic } from './components/Traffic'
import { WakingUp } from './components/WakingUp'
import { LatencyChart, StatusCodeChart, ThroughputChart, useChartColors } from './components/charts'
import { useAdmin } from './hooks/useAdmin'
import { useGatewayStream } from './hooks/useGatewayStream'
import { useTheme } from './hooks/useTheme'
import { formatDuration } from './lib/format'

export default function App() {
  const { theme, toggle } = useTheme()
  const { toasts, notify, dismiss } = useToasts()
  const { snapshot, requests, connection, startedAt, paused, togglePaused } = useGatewayStream()
  const admin = useAdmin(notify, snapshot !== null)
  const colors = useChartColors(theme)

  return (
    <div className="mx-auto flex max-w-[1440px] flex-col gap-4 px-4 py-5 sm:px-6 lg:py-7">
      <Header
        connection={connection}
        theme={theme}
        onToggleTheme={toggle}
        token={admin.token}
        onTokenChange={admin.setToken}
        tokenRequired={admin.config?.adminTokenRequired ?? false}
      />

      {!snapshot ? (
        <WakingUp startedAt={startedAt} connection={connection} />
      ) : (
        <main className={`flex flex-col gap-4 transition-opacity ${connection === 'offline' ? 'opacity-60' : ''}`}>
          {connection === 'offline' && (
            <p role="alert" className="rounded-md border border-critical bg-[var(--critical-wash)] px-4 py-2.5 text-[13px] text-ink">
              Lost contact with the gateway. Showing the last data received; this page reconnects on its own.
            </p>
          )}
          <StatRow snapshot={snapshot} />
          <RequestPath snapshot={snapshot} />

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-[3fr_2fr]">
            <ThroughputChart timeline={snapshot.timeline} colors={colors} />
            <LatencyChart timeline={snapshot.timeline} latency={snapshot.latency} colors={colors} />
          </div>

          <Backends backends={snapshot.backends} admin={admin} />

          <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-3">
            <Controls admin={admin} />
            <div className="flex flex-col gap-4">
              <Traffic traffic={snapshot.traffic} admin={admin} />
              <StatusCodeChart statusCodes={snapshot.statusCodes} colors={colors} />
            </div>
            <CircuitEvents events={snapshot.circuitEvents} />
          </div>

          <RequestLog requests={requests} paused={paused} onTogglePause={togglePaused} />

          <footer className="flex flex-wrap justify-between gap-2 pb-2 pt-1 text-[12px] text-muted">
            <span>
              Gateway up {formatDuration(snapshot.uptimeSeconds)}, {snapshot.mode} backends
            </span>
            <a className="underline decoration-line-strong underline-offset-2 hover:text-ink" href="https://github.com/Shashwat0906/GatePulse">
              Source on GitHub
            </a>
          </footer>
        </main>
      )}

      <Toasts toasts={toasts} dismiss={dismiss} />
    </div>
  )
}
