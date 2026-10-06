import { useCallback, useEffect, useState } from 'react'
import { adminRequest, getJson } from '../lib/api'
import { readStored, writeStored } from '../lib/storage'

const TOKEN_KEY = 'gatepulse-admin-token'

/**
 * Runtime config plus the admin token, and one `run` helper that performs a change, refreshes
 * the config from the response, and reports success or failure as a toast.
 */
export function useAdmin(notify, online) {
  const [config, setConfig] = useState(null)
  const [token, setTokenState] = useState(() => readStored(TOKEN_KEY, ''))
  const [busy, setBusy] = useState(null)

  const setToken = useCallback((value) => {
    setTokenState(value)
    writeStored(TOKEN_KEY, value)
  }, [])

  const refresh = useCallback(async () => {
    try {
      setConfig(await getJson('/admin/config'))
    } catch {
      // The stream hook already shows connection problems; try again on the next refresh.
    }
  }, [])

  // Load the config once the gateway is reachable (it may still be waking up at first).
  useEffect(() => {
    if (!online || config) return undefined
    let cancelled = false
    let retry = null
    const load = () =>
      getJson('/admin/config')
        .then((loaded) => !cancelled && setConfig(loaded))
        .catch(() => {
          if (!cancelled) retry = setTimeout(load, 3000) // still waking up: try again shortly
        })
    load()
    return () => {
      cancelled = true
      clearTimeout(retry)
    }
  }, [online, config])

  /**
   * @param key      identifies the action, so its button can show a busy state
   * @param success  toast text when it works
   */
  const run = useCallback(
    async (key, method, path, body, success) => {
      setBusy(key)
      try {
        const result = await adminRequest(method, path, body, token)
        if (result && result.loadBalancer && result.rateLimit) setConfig(result)
        else refresh()
        notify({ tone: 'good', text: success })
        return true
      } catch (error) {
        const text =
          error.status === 401
            ? 'This gateway needs an admin token for changes. Add it under Admin token.'
            : error.status === 403
              ? 'The admin token was rejected. Check it under Admin token.'
              : error.name === 'TimeoutError'
                ? 'The gateway did not answer in time.'
                : error.message
        notify({ tone: 'critical', text })
        return false
      } finally {
        setBusy(null)
      }
    },
    [token, notify, refresh],
  )

  return { config, token, setToken, run, busy }
}
