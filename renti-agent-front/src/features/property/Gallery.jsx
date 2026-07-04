import { useEffect, useState } from 'react'

import { imgSrc } from '../workspace/utils.js'

/** 无图占位块 */
function GalleryPlaceholder({ text = '暂无图片' }) {
  return (
    <div className="flex h-full w-full flex-col items-center justify-center gap-2 bg-gradient-to-br from-brand-50 via-sky-50 to-ink-100 text-ink-400">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-8 w-8" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="m2.25 15.75 5.159-5.159a2.25 2.25 0 0 1 3.182 0l5.159 5.159m-1.5-1.5 1.409-1.409a2.25 2.25 0 0 1 3.182 0l2.909 2.909M3.75 21h16.5A1.5 1.5 0 0 0 21.75 19.5V4.5A1.5 1.5 0 0 0 20.25 3H3.75A1.5 1.5 0 0 0 2.25 4.5v15A1.5 1.5 0 0 0 3.75 21Z"
        />
      </svg>
      <p className="text-xs">{text}</p>
    </div>
  )
}

/** 图集：主图大图 + 缩略图行；图片加载失败自动降级为占位 */
function Gallery({ title, images = [] }) {
  const [index, setIndex] = useState(0)
  const [failed, setFailed] = useState({})

  useEffect(() => {
    setIndex(0)
    setFailed({})
  }, [images])

  const list = images.filter(Boolean)
  const active = list[index] || ''
  const activeOk = active && !failed[active]

  return (
    <section aria-label="房源图集">
      <div className="relative aspect-[16/10] overflow-hidden rounded-2xl bg-ink-100 shadow-card ring-1 ring-ink-100/60">
        {activeOk ? (
          <img
            src={imgSrc(active)}
            alt={`${title} 主图`}
            className="h-full w-full object-cover"
            onError={() => setFailed((prev) => ({ ...prev, [active]: true }))}
          />
        ) : (
          <GalleryPlaceholder text={list.length > 0 ? '图片暂不可用，来源图片可能已过期' : '当前房源没有可展示的图片'} />
        )}
        {list.length > 1 && (
          <span className="absolute bottom-3 right-3 rounded-full bg-surface-deep/75 px-2.5 py-1 text-xs font-medium text-white backdrop-blur">
            {index + 1} / {list.length}
          </span>
        )}
      </div>

      {list.length > 1 && (
        <div className="mt-3 flex gap-2 overflow-x-auto pb-1 scrollbar-thin">
          {list.map((image, thumbIndex) => (
            <button
              key={image}
              type="button"
              onClick={() => setIndex(thumbIndex)}
              aria-label={`查看第 ${thumbIndex + 1} 张图片`}
              aria-pressed={thumbIndex === index}
              className={[
                'h-16 w-24 shrink-0 overflow-hidden rounded-xl ring-2 transition',
                thumbIndex === index ? 'ring-brand-500' : 'ring-transparent hover:ring-ink-200',
              ].join(' ')}
            >
              {failed[image] ? (
                <GalleryPlaceholder text="" />
              ) : (
                <img
                  src={imgSrc(image)}
                  alt={`${title} 缩略图 ${thumbIndex + 1}`}
                  loading="lazy"
                  className="h-full w-full object-cover"
                  onError={() => setFailed((prev) => ({ ...prev, [image]: true }))}
                />
              )}
            </button>
          ))}
        </div>
      )}
    </section>
  )
}

export default Gallery
