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
        'overflow-auto rounded-xl bg-ink-950 px-4 py-3 text-xs leading-5 text-ink-100 scrollbar-thin',
        maxHeight,
        className,
      ].join(' ')}
    >
      {text}
    </pre>
  )
}

export default JsonBlock
