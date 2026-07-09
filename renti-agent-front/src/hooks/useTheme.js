import { useEffect, useState } from 'react'

export const THEME_STORAGE_KEY = 'renti.theme'
export const THEME_CHANGE_EVENT = 'renti:theme-change'
export const THEMES = ['dark', 'light']

function normalizeTheme(value) {
  return THEMES.includes(value) ? value : 'dark'
}

export function getInitialTheme() {
  if (typeof window === 'undefined') return 'dark'
  return normalizeTheme(window.localStorage.getItem(THEME_STORAGE_KEY))
}

export function applyTheme(theme) {
  const nextTheme = normalizeTheme(theme)
  if (typeof document === 'undefined') return nextTheme
  document.documentElement.dataset.theme = nextTheme
  document.documentElement.style.colorScheme = nextTheme
  return nextTheme
}

export function persistTheme(theme) {
  const nextTheme = applyTheme(theme)
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(THEME_STORAGE_KEY, nextTheme)
    window.dispatchEvent(new CustomEvent(THEME_CHANGE_EVENT, { detail: nextTheme }))
  }
  return nextTheme
}

export function useThemePreference() {
  const [theme, setTheme] = useState(() => applyTheme(getInitialTheme()))

  useEffect(() => {
    const syncTheme = (event) => {
      const nextTheme = event.type === 'storage'
        ? event.newValue
        : event.detail
      setTheme(applyTheme(nextTheme))
    }

    window.addEventListener(THEME_CHANGE_EVENT, syncTheme)
    window.addEventListener('storage', syncTheme)
    return () => {
      window.removeEventListener(THEME_CHANGE_EVENT, syncTheme)
      window.removeEventListener('storage', syncTheme)
    }
  }, [])

  const setPreferredTheme = (nextTheme) => {
    setTheme(persistTheme(nextTheme))
  }

  const toggleTheme = () => {
    setPreferredTheme(theme === 'dark' ? 'light' : 'dark')
  }

  return {
    theme,
    isDark: theme === 'dark',
    setTheme: setPreferredTheme,
    toggleTheme,
  }
}

