import { useCallback, useEffect, useRef, useState } from 'react'
import { apiUrl, getJson } from '../lib/api'

const MAX_REQUESTS = 150
const POLL_INTERVAL_MS = 2000
const STREAM_SILENCE_MS = 6000
const STREAM_RETRY_MS = 15000

/**
 * Live data from the gateway.
 *
 * Primary channel: Server-Sent Events on /admin/stream ("metrics" every second plus
 * "requests" batches). The browser's EventSource reconnects by itself after a drop.
 *
 * Fallback: if the stream is silent for a few seconds (a proxy that buffers SSE, a server
 * still waking up), poll /admin/metrics and /admin/requests every 2s, and keep retrying the
 * stream in the background. The first stream message switches polling off again.
 *
 * connection: 'connecting' (no data yet) | 'live' | 'reconnecting' | 'polling' | 'offline'
 */
export function useGatewayStream() {
  const [snapshot, setSnapshot] = useState(null)
  const [requests, setRequests] = useState([])
  const [connection, setConnection] = useState('connecting')
  const [startedAt] = useState(() => Date.now())
  const [paused, setPaused] = useState(false)

  const lastSeq = useRef(0)
  const pausedRef = useRef(false)
  const hasData = useRef(false)

  const addRequests = useCallback((batch) => {
    if (!Array.isArray(batch) || batch.length === 0) return
    const fresh = batch.filter((r) => r.seq > lastSeq.current)
    if (fresh.length === 0) return
    lastSeq.current = fresh[fresh.length - 1].seq
    if (pausedRef.current) return
    setRequests((current) => [...fresh.reverse(), ...current].slice(0, MAX_REQUESTS))
  }, [])

  const togglePaused = useCallback(() => {
    pausedRef.current = !pausedRef.current
    setPaused(pausedRef.current)
  }, [])

  useEffect(() => {
    let stopped = false
    let source = null
    let pollTimer = null
    let retryTimer = null
    let lastMessageAt = Date.now() // give the stream a grace period before falling back

    const onSnapshot = (data) => {
      lastMessageAt = Date.now()
      hasData.current = true
      setSnapshot(data)
    }

    const stopPolling = () => {
      clearInterval(pollTimer)
      clearInterval(retryTimer)
      pollTimer = null
      retryTimer = null
    }

    const poll = async () => {
      try {
        const [metrics, log] = await Promise.all([
          getJson('/admin/metrics'),
          getJson(`/admin/requests?since=${lastSeq.current}&limit=100`),
        ])
        if (stopped) return
        onSnapshot(metrics)
        addRequests(log.requests)
        setConnection('polling')
      } catch {
        if (!stopped) setConnection(hasData.current ? 'offline' : 'connecting')
      }
    }

    const openStream = () => {
      source?.close()
      source = new EventSource(apiUrl('/admin/stream'))
      source.addEventListener('metrics', (event) => {
        onSnapshot(JSON.parse(event.data))
        setConnection('live')
        stopPolling()
      })
      source.addEventListener('requests', (event) => addRequests(JSON.parse(event.data)))
      source.onerror = () => {
        if (stopped) return
        // EventSource retries on its own; just show that the link is down for now.
        setConnection((c) => (c === 'live' ? 'reconnecting' : c))
      }
    }

    const startPolling = () => {
      if (pollTimer) return
      poll()
      pollTimer = setInterval(poll, POLL_INTERVAL_MS)
      retryTimer = setInterval(openStream, STREAM_RETRY_MS)
    }

    openStream()
    const watchdog = setInterval(() => {
      if (Date.now() - lastMessageAt > STREAM_SILENCE_MS) startPolling()
    }, 1000)

    return () => {
      stopped = true
      source?.close()
      stopPolling()
      clearInterval(watchdog)
    }
  }, [addRequests])

  return { snapshot, requests, connection, startedAt, paused, togglePaused }
}
