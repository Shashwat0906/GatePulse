// localStorage can be missing or throw (private windows, blocked site data). These helpers
// treat it as a convenience: if it fails, the app keeps working with in-memory state.

export function readStored(key, fallback = null) {
  try {
    const value = window.localStorage.getItem(key)
    return value === null ? fallback : value
  } catch {
    return fallback
  }
}

export function writeStored(key, value) {
  try {
    if (value === null || value === undefined || value === '') window.localStorage.removeItem(key)
    else window.localStorage.setItem(key, value)
  } catch {
    // Storage unavailable: the value simply is not remembered across reloads.
  }
}
