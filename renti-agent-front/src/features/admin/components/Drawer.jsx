import { useEffect, useState } from 'react'
import { createPortal } from 'react-dom'

/**
 * 右侧滑出抽屉：详情面板统一容器。
 *
 * @param {object} props
 * @param {boolean} props.open
 * @param {() => void} props.onClose
 * @param {string} [props.title]
 * @param {import('react').ReactNode} [props.subtitle]
 * @param {'md'|'lg'} [props.size] md=480px lg=640px
 */
function Drawer({ open, onClose, title, subtitle, size = 'md', children, footer }) {
  const [mounted, setMounted] = useState(open)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    if (open) {
      setMounted(true)
      const raf = requestAnimationFrame(() => setVisible(true))
      return () => cancelAnimationFrame(raf)
    }
    setVisible(false)
    const timer = setTimeout(() => setMounted(false), 250)
    return () => clearTimeout(timer)
  }, [open])

  useEffect(() => {
    if (!open) return undefined
    const handleKey = (event) => {
      if (event.key === 'Escape') onClose?.()
    }
    document.addEventListener('keydown', handleKey)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', handleKey)
      document.body.style.overflow = ''
    }
  }, [open, onClose])

  if (!mounted) return null

  const width = size === 'lg' ? 'w-full max-w-[640px]' : 'w-full max-w-[480px]'

  return createPortal(
    <div className="fixed inset-0 z-50" role="dialog" aria-modal="true" aria-label={title}>
      <div
        className={[
          'absolute inset-0 bg-ink-950/40 backdrop-blur-sm transition-opacity duration-200',
          visible ? 'opacity-100' : 'opacity-0',
        ].join(' ')}
        onClick={onClose}
      />
      <aside
        className={[
          'absolute inset-y-0 right-0 flex flex-col bg-white shadow-float transition-transform duration-250 ease-out',
          width,
          visible ? 'translate-x-0' : 'translate-x-full',
        ].join(' ')}
      >
        <header className="flex items-start justify-between gap-3 border-b border-ink-100 px-5 py-4">
          <div className="min-w-0">
            <h3 className="truncate text-base font-semibold text-ink-900">{title}</h3>
            {subtitle && <div className="mt-0.5 text-xs text-ink-400">{subtitle}</div>}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="rounded-full p-1.5 text-ink-400 transition hover:bg-ink-100 hover:text-ink-700"
          >
            <svg className="h-4 w-4" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z" />
            </svg>
          </button>
        </header>
        <div className="flex-1 overflow-y-auto px-5 py-4 scrollbar-thin">{children}</div>
        {footer && (
          <footer className="flex justify-end gap-2 border-t border-ink-100 px-5 py-3.5">{footer}</footer>
        )}
      </aside>
    </div>,
    document.body,
  )
}

export default Drawer
