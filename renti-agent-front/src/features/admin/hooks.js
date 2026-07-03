import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * 异步数据三态 Hook：loading / error / data + reload。
 *
 * @param {() => Promise<any>} fetcher 请求函数（用 useCallback 包裹或依赖 deps）
 * @param {Array} deps 变化时自动重新请求
 */
export function useAsyncData(fetcher, deps = []) {
  const [state, setState] = useState({ loading: true, error: null, data: null })
  const seqRef = useRef(0)

  const load = useCallback(async () => {
    const seq = (seqRef.current += 1)
    setState((prev) => ({ ...prev, loading: true, error: null }))
    try {
      const data = await fetcher()
      if (seqRef.current === seq) setState({ loading: false, error: null, data })
    } catch (error) {
      if (seqRef.current === seq) setState((prev) => ({ ...prev, loading: false, error }))
    }
  }, deps) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    load()
  }, [load])

  return { ...state, reload: load, setData: (data) => setState((prev) => ({ ...prev, data })) }
}

/**
 * 轻提示 Hook：flash(message) 后 message 保持 duration 毫秒自动清空。
 */
export function useFlash(duration = 2000) {
  const [message, setMessage] = useState('')
  const timerRef = useRef(null)

  const flash = useCallback(
    (text) => {
      setMessage(text)
      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => setMessage(''), duration)
    },
    [duration],
  )

  useEffect(() => () => timerRef.current && clearTimeout(timerRef.current), [])

  return [message, flash]
}
