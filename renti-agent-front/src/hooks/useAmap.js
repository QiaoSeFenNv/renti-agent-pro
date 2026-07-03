import { useEffect, useRef, useState } from 'react'
import AMapLoader from '@amap/amap-jsapi-loader'

let amapPromise = null

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
 * @param {'normal'|'light'} [options.styleName] 地图风格
 * @returns {{ containerRef, map, amap, ready, error }}
 */
export function useAmap({ center = [121.4737, 31.2304], zoom = 12, styleName = 'light' } = {}) {
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
          mapStyle: styleName === 'light' ? 'amap://styles/whitesmoke' : 'amap://styles/normal',
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

  return { containerRef, map: mapRef.current, mapRef, amap, ready, error }
}

export default useAmap
