import { toJson } from '../utils.js'

/**
 * JSON 数据块：深色代码卡，自动格式化对象/字符串。
 */
function JsonBlock({ value, maxHeight = 'max-h-96', className = '' }) {
  const text = toJson(value)
  if (!text) {
    return <p className={['text-xs text-ink-400', className].join(' ')}>（空）</p>
  }
  return (
    <pre
      className={[
        'overflow-auto rounded-xl bg-black/50 px-4 py-3 font-mono text-xs leading-5 text-ink-800 ring-1 ring-white/[0.06] scrollbar-thin',
        maxHeight,
        className,
      ].join(' ')}
    >
      {text}
    </pre>
  )
}

export default JsonBlock
