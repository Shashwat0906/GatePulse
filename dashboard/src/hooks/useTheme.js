import { useCallback, useState } from 'react'
import { writeStored } from '../lib/storage'

/** Light/dark theme. index.html applies the saved or OS theme before first paint. */
export function useTheme() {
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme || 'light')

  const toggle = useCallback(() => {
    setTheme((current) => {
      const next = current === 'dark' ? 'light' : 'dark'
      document.documentElement.dataset.theme = next
      writeStored('gatepulse-theme', next)
      return next
    })
  }, [])

  return { theme, toggle }
}
