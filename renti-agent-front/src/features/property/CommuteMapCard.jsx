import { useEffect, useRef } from 'react'

import { EmptyState } from '../../components/ui/Feedback.jsx'
import useAmap from '../../hooks/useAmap.js'
import { escapeHtml, formatDistance, toNumber } from '../workspace/utils.js'
import { haversineMeters, isCoordPoint } from './detailUtils.js'

const DEFAULT_CENTER = [121.4737, 31.2304]

/**
 * 通勤小地图：目标点 + 房源两个 marker、直线 Polyline、周边配套小 marker。
 */
function CommuteMapCard({ commuteMap }) {
  const target = commuteMap?.target
  const property = commuteMap?.property
  const targetOk = isCoordPoint(target)
  const propertyOk = isCoordPoint(property)
  const hasMap = targetOk || propertyOk
  const hasTargetDistance = commuteMap?.hasTargetDistance !== false

  const initialCenter = propertyOk
    ? [toNumber(property.longitude), toNumber(property.latitude)]
    : targetOk
      ? [toNumber(target.longitude), toNumber(target.latitude)]
      : DEFAULT_CENTER
  const { containerRef, mapRef, amap, ready, error } = useAmap({ center: initialCenter, zoom: 14 })
  const overlaysRef = useRef([])

  const distanceMeters = (() => {
    const declared = toNumber(commuteMap?.distanceMeters)
    if (Number.isFinite(declared) && declared > 0) return declared
    if (targetOk && propertyOk) return haversineMeters(target, property)
    return Number.NaN
  })()

  useEffect(() => {
    const map = mapRef.current
    if (!ready || !map || !amap || !hasMap) return

    overlaysRef.current.forEach((overlay) => overlay.setMap?.(null))
    overlaysRef.current = []
    const overlays = []

    const buildPin = (label, tone) => {
      const element = document.createElement('div')
      element.className = 'flex flex-col items-center'
      element.innerHTML = `
        <span class="whitespace-nowrap rounded-full px-2.5 py-1 text-xs font-medium text-white shadow-float ring-1 backdrop-blur ${
          tone === 'target' ? 'bg-surface-deep/90 ring-cyan-400/40' : 'bg-brand-600 ring-white/20'
        }">${escapeHtml(label)}</span>
        <span class="mt-1 h-2.5 w-2.5 rounded-full border-2 border-surface-deep shadow ${
          tone === 'target' ? 'bg-cyan-300' : 'bg-brand-400'
        }"></span>`
      return element
    }

    if (targetOk) {
      overlays.push(
        new amap.Marker({
          position: [toNumber(target.longitude), toNumber(target.latitude)],
          content: buildPin(target.label || '目标点', 'target'),
          anchor: 'bottom-center',
          zIndex: 120,
          map,
        }),
      )
    }
    if (propertyOk) {
      overlays.push(
        new amap.Marker({
          position: [toNumber(property.longitude), toNumber(property.latitude)],
          content: buildPin(property.label || '房源', 'property'),
          anchor: 'bottom-center',
          zIndex: 120,
          map,
        }),
      )
    }
    if (targetOk && propertyOk) {
      const line = new amap.Polyline({
        path: [
          [toNumber(target.longitude), toNumber(target.latitude)],
          [toNumber(property.longitude), toNumber(property.latitude)],
        ],
        strokeColor: '#477bff',
        strokeWeight: 3,
        strokeOpacity: 0.85,
        strokeStyle: 'dashed',
        bubble: true,
      })
      map.add(line)
      overlays.push(line)
    }

    const amenities = Array.isArray(commuteMap?.amenities) ? commuteMap.amenities : []
    amenities.filter(isCoordPoint).forEach((amenity) => {
      const element = document.createElement('div')
      element.className =
        'whitespace-nowrap rounded-full bg-surface-deep/85 px-2 py-0.5 text-[10px] font-medium text-ink-700 ring-1 ring-white/15 backdrop-blur'
      element.textContent = `${amenity.label || '配套'}${
        Number.isFinite(toNumber(amenity.distanceMeters)) ? ` · ${formatDistance(amenity.distanceMeters)}` : ''
      }`
      overlays.push(
        new amap.Marker({
          position: [toNumber(amenity.longitude), toNumber(amenity.latitude)],
          content: element,
          anchor: 'center',
          zIndex: 100,
          map,
        }),
      )
    })

    overlaysRef.current = overlays
    try {
      map.setFitView(overlays, false, [48, 48, 48, 48])
    } catch {
      // setFitView 在个别版本对混合覆盖物有兼容问题，忽略即可
    }

    return () => {
      overlaysRef.current.forEach((overlay) => overlay.setMap?.(null))
      overlaysRef.current = []
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, amap, commuteMap])

  return (
    <div>
      {!hasTargetDistance && (
        <p className="mb-2 rounded-xl bg-amber-50 px-3 py-2 text-xs leading-5 text-amber-800 ring-1 ring-amber-100">
          本次搜索未指定目标点，图示距离仅供参考。
        </p>
      )}
      {hasMap ? (
        <div className="relative h-64 overflow-hidden rounded-xl ring-1 ring-white/[0.08]">
          <div ref={containerRef} className="absolute inset-0" role="application" aria-label="通勤直线距离地图" />
          {error && (
            <div className="absolute inset-0 flex items-center justify-center bg-ink-50">
              <EmptyState icon="🛰️" title="通勤地图加载失败" className="py-0" />
            </div>
          )}
          {Number.isFinite(distanceMeters) && distanceMeters > 0 && (
            <div className="absolute bottom-3 left-3 rounded-full bg-surface/85 px-3 py-1.5 font-mono text-xs font-medium text-ink-800 ring-1 ring-white/10 backdrop-blur">
              直线距离约 {formatDistance(distanceMeters)}
            </div>
          )}
        </div>
      ) : (
        <EmptyState icon="📍" title="暂无通勤坐标" description="缺少目标点或房源坐标，无法绘制通勤地图。" className="py-8" />
      )}
    </div>
  )
}

export default CommuteMapCard
