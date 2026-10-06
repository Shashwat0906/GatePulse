import { useMemo, useState } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  LabelList,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { formatClock, formatCount, formatMs } from '../lib/format'
import { Button } from './ui'

/**
 * Charts follow one set of rules: series colors come from the validated categorical palette
 * in fixed slot order, marks are thin, grid and axes are hairlines, text uses ink tokens
 * (never the series color), every multi-series chart has a legend, and every chart has a
 * table view so no value is reachable only by hovering.
 */

/** Reads the theme's CSS variables so Recharts (SVG attributes) gets concrete colors. */
export function useChartColors(theme) {
  return useMemo(() => {
    const css = getComputedStyle(document.documentElement)
    const v = (name) => css.getPropertyValue(name).trim()
    return {
      s1: v('--series-1'),
      s2: v('--series-2'),
      s3: v('--series-3'),
      s4: v('--series-4'),
      surface: v('--surface'),
      grid: v('--line'),
      axis: v('--line-strong'),
      muted: v('--muted'),
      ink: v('--ink'),
      cursor: v('--surface-sunk'),
    }
    // theme is the trigger: the variables change when it does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [theme])
}

function ChartFrame({ title, description, legend, table, children }) {
  const [showTable, setShowTable] = useState(false)
  return (
    <section className="flex min-w-0 flex-col rounded-lg border border-line bg-surface px-5 pb-4 pt-4">
      <header className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h2 className="text-[15px] font-semibold text-ink">{title}</h2>
          {description && <p className="text-[13px] text-muted">{description}</p>}
        </div>
        <Button onClick={() => setShowTable((s) => !s)} aria-pressed={showTable} className="h-7 px-2.5">
          {showTable ? 'Show chart' : 'Show table'}
        </Button>
      </header>
      {legend && !showTable && <Legend items={legend} />}
      <div className="mt-2 min-h-0 flex-1">{showTable ? table : children}</div>
    </section>
  )
}

function Legend({ items }) {
  return (
    <ul className="mt-3 flex flex-wrap gap-x-4 gap-y-1">
      {items.map((item) => (
        <li key={item.label} className="flex items-center gap-1.5 text-[12px] text-ink-2">
          {item.shape === 'line' ? (
            <span aria-hidden="true" className="h-0.5 w-3.5 rounded-full" style={{ background: item.color }} />
          ) : (
            <span aria-hidden="true" className="size-2.5 rounded-[2px]" style={{ background: item.color }} />
          )}
          {item.label}
        </li>
      ))}
    </ul>
  )
}

function TooltipCard({ title, rows }) {
  return (
    <div className="min-w-40 rounded-md border border-line-strong bg-surface px-3 py-2 text-[12px] shadow-[0_6px_20px_rgb(0_0_0/0.12)]">
      <p className="tnum mb-1 text-muted">{title}</p>
      {rows.map((row) => (
        <p key={row.label} className="flex items-center justify-between gap-4">
          <span className="flex items-center gap-1.5 text-ink-2">
            <span aria-hidden="true" className="h-0.5 w-3 rounded-full" style={{ background: row.color }} />
            {row.label}
          </span>
          <span className="tnum font-semibold text-ink">{row.value}</span>
        </p>
      ))}
    </div>
  )
}

function DataTable({ columns, rows }) {
  return (
    <div className="max-h-64 overflow-auto rounded-md border border-line">
      <table className="w-full text-left text-[12px]">
        <thead className="sticky top-0 bg-sunk text-ink-2">
          <tr>
            {columns.map((c) => (
              <th key={c.key} scope="col" className={`px-3 py-1.5 font-medium ${c.numeric ? 'text-right' : ''}`}>
                {c.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row, i) => (
            <tr key={i} className="border-t border-line">
              {columns.map((c) => (
                <td key={c.key} className={`tnum px-3 py-1 text-ink ${c.numeric ? 'text-right' : ''}`}>
                  {c.format ? c.format(row[c.key]) : row[c.key]}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const axisTick = (colors) => ({ fill: colors.muted, fontSize: 11 })

/** Short latency ticks that fit the axis: 0, 250ms, 1.5s. */
function formatAxisMs(ms) {
  if (ms === 0) return '0'
  return ms >= 1000 ? `${+(ms / 1000).toFixed(1)}s` : `${+ms.toFixed(ms < 10 ? 1 : 0)}ms`
}

// ---------------------------------------------------------------- throughput

const THROUGHPUT_SERIES = [
  { key: 'served', label: 'Served by a backend', color: 's1' },
  { key: 'rateLimited', label: 'Rate limited (429)', color: 's2' },
  { key: 'cached', label: 'Answered from cache', color: 's3' },
  { key: 'errors', label: 'Server errors (5xx)', color: 's4' },
]

export function ThroughputChart({ timeline, colors }) {
  const data = useMemo(
    () =>
      timeline.map((p) => ({
        t: p.t,
        label: formatClock(p.t),
        served: Math.max(0, p.requests - p.rateLimited - p.cacheHits - p.errors),
        rateLimited: p.rateLimited,
        cached: p.cacheHits,
        errors: p.errors,
        total: p.requests,
      })),
    [timeline],
  )
  const quiet = data.every((d) => d.total === 0)

  return (
    <ChartFrame
      title="Requests per second"
      description="Each column is one second, split by what happened to the requests"
      legend={THROUGHPUT_SERIES.map((s) => ({ label: s.label, color: colors[s.color], shape: 'rect' }))}
      table={
        <DataTable
          columns={[
            { key: 'label', label: 'Second' },
            { key: 'total', label: 'Total', numeric: true },
            ...THROUGHPUT_SERIES.map((s) => ({ key: s.key, label: s.label, numeric: true })),
          ]}
          rows={[...data].reverse()}
        />
      }
    >
      <div className="relative h-64">
        <ResponsiveContainer width="100%" height="100%">
          <BarChart data={data} margin={{ top: 8, right: 4, bottom: 0, left: -12 }} barCategoryGap={1}>
            <CartesianGrid vertical={false} stroke={colors.grid} />
            <XAxis dataKey="label" tick={axisTick(colors)} tickLine={false} axisLine={{ stroke: colors.axis }} interval={14} minTickGap={24} />
            <YAxis tick={axisTick(colors)} tickLine={false} axisLine={false} allowDecimals={false} width={44} />
            <Tooltip
              cursor={{ fill: colors.cursor }}
              isAnimationActive={false}
              content={({ active, payload }) =>
                active && payload?.length ? (
                  <TooltipCard
                    title={`${payload[0].payload.label}, ${formatCount(payload[0].payload.total)} requests`}
                    rows={THROUGHPUT_SERIES.map((s) => ({
                      label: s.label,
                      color: colors[s.color],
                      value: formatCount(payload[0].payload[s.key]),
                    }))}
                  />
                ) : null
              }
            />
            {THROUGHPUT_SERIES.map((s) => (
              <Bar
                key={s.key}
                dataKey={s.key}
                stackId="requests"
                fill={colors[s.color]}
                stroke={colors.surface}
                strokeWidth={1}
                maxBarSize={24}
                isAnimationActive={false}
              />
            ))}
          </BarChart>
        </ResponsiveContainer>
        {quiet && <EmptyNote>No traffic in the last minute. Start steady traffic or send a spike below.</EmptyNote>}
      </div>
    </ChartFrame>
  )
}

function EmptyNote({ children }) {
  return (
    <div className="pointer-events-none absolute inset-0 flex items-center justify-center px-8 text-center text-[13px] text-muted">
      <span className="rounded-md bg-surface px-3 py-1.5">{children}</span>
    </div>
  )
}

// ---------------------------------------------------------------- latency

const LATENCY_SERIES = [
  { key: 'p50', label: 'p50 (median)', color: 's1' },
  { key: 'p95', label: 'p95', color: 's2' },
  { key: 'p99', label: 'p99', color: 's3' },
]

export function LatencyChart({ timeline, latency, colors }) {
  const data = useMemo(
    () =>
      timeline.map((p) => ({
        label: formatClock(p.t),
        // A second with no traffic has no latency; leave a gap rather than drawing a false zero.
        p50: p.requests ? p.p50 : null,
        p95: p.requests ? p.p95 : null,
        p99: p.requests ? p.p99 : null,
      })),
    [timeline],
  )

  return (
    <ChartFrame
      title="Latency percentiles"
      description={
        latency.samples === 0
          ? 'No requests in the last 10 seconds'
          : `Last 10 seconds: p50 ${formatMs(latency.p50)}, p95 ${formatMs(latency.p95)}, p99 ${formatMs(latency.p99)}`
      }
      legend={LATENCY_SERIES.map((s) => ({ label: s.label, color: colors[s.color], shape: 'line' }))}
      table={
        <DataTable
          columns={[
            { key: 'label', label: 'Second' },
            ...LATENCY_SERIES.map((s) => ({ key: s.key, label: `${s.label} (ms)`, numeric: true, format: (v) => (v == null ? '–' : v.toFixed(2)) })),
          ]}
          rows={[...data].reverse()}
        />
      }
    >
      <div className="h-64">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -12 }}>
            <CartesianGrid vertical={false} stroke={colors.grid} />
            <XAxis dataKey="label" tick={axisTick(colors)} tickLine={false} axisLine={{ stroke: colors.axis }} interval={14} minTickGap={24} />
            <YAxis tick={axisTick(colors)} tickLine={false} axisLine={false} width={54} tickFormatter={formatAxisMs} />
            <Tooltip
              cursor={{ stroke: colors.axis, strokeWidth: 1 }}
              isAnimationActive={false}
              content={({ active, payload, label }) =>
                active && payload?.length ? (
                  <TooltipCard
                    title={label}
                    rows={LATENCY_SERIES.map((s) => ({
                      label: s.label,
                      color: colors[s.color],
                      value: payload[0].payload[s.key] == null ? 'no traffic' : formatMs(payload[0].payload[s.key]),
                    }))}
                  />
                ) : null
              }
            />
            {LATENCY_SERIES.map((s) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                stroke={colors[s.color]}
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
                dot={false}
                activeDot={{ r: 4, stroke: colors.surface, strokeWidth: 2 }}
                connectNulls={false}
                isAnimationActive={false}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      </div>
    </ChartFrame>
  )
}

// ---------------------------------------------------------------- status codes

export function StatusCodeChart({ statusCodes, colors }) {
  const data = useMemo(
    () =>
      Object.entries(statusCodes)
        .map(([code, count]) => ({ code, count, label: describeStatus(code) }))
        .sort((a, b) => b.count - a.count),
    [statusCodes],
  )
  const total = data.reduce((t, d) => t + d.count, 0)

  return (
    <ChartFrame
      title="Status codes"
      description="Every response since the gateway started"
      table={
        <DataTable
          columns={[
            { key: 'code', label: 'Status' },
            { key: 'label', label: 'Meaning' },
            { key: 'count', label: 'Responses', numeric: true, format: formatCount },
          ]}
          rows={data}
        />
      }
    >
      {data.length === 0 ? (
        <p className="flex h-64 items-center justify-center text-[13px] text-muted">No responses yet.</p>
      ) : (
        <div style={{ height: Math.max(120, data.length * 34 + 16) }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ top: 4, right: 56, bottom: 4, left: 0 }} barCategoryGap={8}>
              <XAxis type="number" hide domain={[0, 'dataMax']} />
              <YAxis
                type="category"
                dataKey="code"
                tick={{ fill: colors.ink, fontSize: 12 }}
                tickLine={false}
                axisLine={{ stroke: colors.axis }}
                width={40}
              />
              <Tooltip
                cursor={{ fill: colors.cursor }}
                isAnimationActive={false}
                content={({ active, payload }) =>
                  active && payload?.length ? (
                    <TooltipCard
                      title={`${payload[0].payload.code} ${payload[0].payload.label}`}
                      rows={[
                        {
                          label: `${((payload[0].payload.count / total) * 100).toFixed(1)}% of responses`,
                          color: colors.s1,
                          value: formatCount(payload[0].payload.count),
                        },
                      ]}
                    />
                  ) : null
                }
              />
              <Bar dataKey="count" fill={colors.s1} radius={[0, 4, 4, 0]} maxBarSize={20} isAnimationActive={false}>
                <LabelList dataKey="count" position="right" formatter={formatCount} fill={colors.ink} fontSize={12} />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </ChartFrame>
  )
}

const STATUS_TEXT = {
  200: 'OK',
  201: 'Created',
  204: 'No content',
  400: 'Bad request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not found',
  429: 'Too many requests',
  500: 'Server error',
  502: 'Bad gateway',
  503: 'Service unavailable',
  504: 'Gateway timeout',
}

function describeStatus(code) {
  return STATUS_TEXT[code] || ''
}
