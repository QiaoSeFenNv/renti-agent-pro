import { useEffect, useState } from 'react'

/**
 * 打字机效果：循环输入/删除一组短语。
 *
 * @param {string[]} phrases 轮播短语
 * @param {object} [options]
 * @param {number} [options.typeSpeed] 输入速度 ms/字符
 * @param {number} [options.deleteSpeed] 删除速度 ms/字符
 * @param {number} [options.holdMs] 输完后的停留时间
 * @returns {string} 当前应展示的文本片段
 */
export function useTypewriter(phrases, { typeSpeed = 110, deleteSpeed = 38, holdMs = 2200 } = {}) {
  const [text, setText] = useState('')
  const [phase, setPhase] = useState({ index: 0, deleting: false })

  useEffect(() => {
    if (!Array.isArray(phrases) || phrases.length === 0) return undefined
    const current = phrases[phase.index % phrases.length]

    // 尊重系统减弱动效偏好：直接完整展示当前短语
    if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setText(current)
      return undefined
    }

    let timer
    if (!phase.deleting) {
      if (text.length < current.length) {
        timer = setTimeout(() => setText(current.slice(0, text.length + 1)), typeSpeed)
      } else {
        timer = setTimeout(() => setPhase((prev) => ({ ...prev, deleting: true })), holdMs)
      }
    } else if (text.length > 0) {
      timer = setTimeout(() => setText(current.slice(0, text.length - 1)), deleteSpeed)
    } else {
      setPhase((prev) => ({ index: (prev.index + 1) % phrases.length, deleting: false }))
    }
    return () => clearTimeout(timer)
  }, [phrases, phase, text, typeSpeed, deleteSpeed, holdMs])

  return text
}

export default useTypewriter
