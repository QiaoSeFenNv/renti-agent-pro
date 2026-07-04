import { useState } from 'react'

/**
 * 密钥输入框：password 型 + 显示切换 + 「已配置」回显 + 可选清除勾选。
 *
 * @param {object} props
 * @param {string} props.label
 * @param {string} props.value
 * @param {(value: string) => void} props.onChange
 * @param {boolean} [props.configured] 服务端是否已保存密钥
 * @param {boolean} [props.clearChecked] 「清除已保存密钥」勾选态（传入才显示）
 * @param {(checked: boolean) => void} [props.onClearChange]
 */
function SecretField({ label, value, onChange, configured, clearChecked, onClearChange, placeholder }) {
  const [visible, setVisible] = useState(false)

  return (
    <div>
      <div className="mb-1.5 flex items-center justify-between">
        <span className="block text-sm font-medium text-ink-700">{label}</span>
        {configured !== undefined && (
          <span className={['text-xs', configured ? 'text-emerald-700' : 'text-ink-400'].join(' ')}>
            {configured ? '已配置' : '未配置'}
          </span>
        )}
      </div>
      <div className="relative">
        <input
          type={visible ? 'text' : 'password'}
          className="h-11 w-full rounded-xl border-0 bg-black/30 px-3.5 pr-16 font-mono text-sm text-ink-900 ring-1 ring-inset ring-white/10 transition placeholder:font-sans placeholder:text-ink-300 focus:bg-black/45 focus:ring-2 focus:ring-brand-500/80"
          placeholder={placeholder ?? '留空保留已保存的密钥'}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          autoComplete="new-password"
          aria-label={label}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          className="absolute inset-y-0 right-0 flex items-center px-3 text-xs font-medium text-ink-400 hover:text-ink-700"
          aria-label={visible ? '隐藏密钥' : '显示密钥'}
        >
          {visible ? '隐藏' : '显示'}
        </button>
      </div>
      {onClearChange && (
        <label className="mt-1.5 flex items-center gap-2 text-xs text-ink-500">
          <input
            type="checkbox"
            className="h-3.5 w-3.5 rounded accent-rose-500"
            checked={Boolean(clearChecked)}
            onChange={(event) => onClearChange(event.target.checked)}
          />
          清除已保存的密钥
        </label>
      )}
    </div>
  )
}

export default SecretField
