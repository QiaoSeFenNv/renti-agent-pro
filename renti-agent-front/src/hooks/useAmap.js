import { useEffect, useRef, useState } from 'react'
import AMapLoader from '@amap/amap-jsapi-loader'

import { THEME_CHANGE_EVENT, THEME_STORAGE_KEY } from './useTheme.js'

let amapPromise = null

const MAP_STYLES = {
  dark: 'amap://styles/dark',
  light: 'amap://styles/whitesmoke',
  normal: 'amap://styles/normal',
}

/** 站点主题对应的地图风格：暗色主题 → 高德暗色，浅色主题 → 标准 */
function themeMapStyle(theme) {
  const current = theme || (typeof document !== 'undefined' ? document.documentElement.dataset.theme : 'dark')
  return current === 'light' ? MAP_STYLES.normal : MAP_STYLES.dark
}

/**
 * 加载高德 JS API（单例）。security code 必须在 load 之前注入 window。
 * @returns {Promise<any>} AMap 命名空间
 */
export function loadAmap() {
  if (!amapPromise) {
    window._AMapSecurityConfig = {
      securityJsCode: import.meta.env.VITE_AMAP_SECURITY_JS_CODE,
    }
    amapPromise = AMapLoader.load({
      key: import.meta.env.VITE_AMAP_JS_KEY,
      version: '2.0',
      plugins: ['AMap.Scale', 'AMap.ToolBar', 'AMap.MarkerCluster'],
    })
  }
  return amapPromise
}

/**
 * 在容器上初始化一张高德地图。
 *
 * @param {object} options
 * @param {[number, number]} [options.center] 初始中心 [lng, lat]
 * @param {number} [options.zoom]
 * @param {'dark'|'normal'|'light'} [options.styleName] 固定地图风格；不传则跟随站点主题（含运行时切换）
 * @returns {{ containerRef, map, amap, ready, error }}
 */
export function useAmap({ center = [121.4737, 31.2304], zoom = 12, styleName } = {}) {
  const containerRef = useRef(null)
  const mapRef = useRef(null)
  const [amap, setAmap] = useState(null)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    let disposed = false

    loadAmap()
      .then((AMap) => {
        if (disposed || !containerRef.current) return
        const map = new AMap.Map(containerRef.current, {
          center,
          zoom,
          mapStyle: styleName ? MAP_STYLES[styleName] ?? themeMapStyle() : themeMapStyle(),
          viewMode: '2D',
        })
        map.addControl(new AMap.Scale())
        mapRef.current = map
        setAmap(() => AMap)
        setReady(true)
      })
      .catch((err) => {
        console.error('[useAmap] 地图加载失败', err)
        setError(err)
      })

    return () => {
      disposed = true
      if (mapRef.current) {
        mapRef.current.destroy()
        mapRef.current = null
      }
    }
    // 地图实例仅创建一次，center/zoom 变化由调用方通过 map.setCenter 处理
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** 未固定风格时跟随主题切换（本页开关 + 其他标签页 storage 同步） */
  useEffect(() => {
    if (styleName) return undefined
    const syncMapStyle = (event) => {
      if (event.type === 'storage' && event.key !== THEME_STORAGE_KEY) return
      const theme = event.type === 'storage' ? event.newValue : event.detail
      mapRef.current?.setMapStyle(themeMapStyle(theme))
    }
    window.addEventListener(THEME_CHANGE_EVENT, syncMapStyle)
    window.addEventListener('storage', syncMapStyle)
    return () => {
      window.removeEventListener(THEME_CHANGE_EVENT, syncMapStyle)
      window.removeEventListener('storage', syncMapStyle)
    }
  }, [styleName])

  return { containerRef, map: mapRef.current, mapRef, amap, ready, error }
}

export default useAmap
