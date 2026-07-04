import { useEffect, useRef } from 'react'

/**
 * 滚动渐显：返回容器 ref，容器内所有 .reveal 元素进入视口时加 .revealed。
 * 配合 index.css 的 .reveal/.revealed 过渡使用；同一元素只触发一次。
 */
export function useReveal() {
  const rootRef = useRef(null)

  useEffect(() => {
    const root = rootRef.current
    if (!root) return undefined
    const targets = root.querySelectorAll('.reveal')
    if (targets.length === 0) return undefined

    if (typeof IntersectionObserver === 'undefined') {
      targets.forEach((el) => el.classList.add('revealed'))
      return undefined
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add('revealed')
            observer.unobserve(entry.target)
          }
        })
      },
      { threshold: 0.12, rootMargin: '0px 0px -40px 0px' },
    )
    targets.forEach((el) => observer.observe(el))
    return () => observer.disconnect()
  }, [])

  return rootRef
}

export default useReveal
