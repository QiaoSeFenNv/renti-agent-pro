import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import SiteLayout from '../layouts/SiteLayout.jsx'
import { cityService, subscriptionService } from '../services/cityService.js'

const FEATURES = [
  {
    title: '地图圈定找房',
    description: '在地图上点选目标位置并设定半径，只看真正到得了的房源，距离与通勤一目了然。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-6 w-6" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm4.5 0c0 5.25-7.5 11.25-7.5 11.25S4.5 15.75 4.5 10.5a7.5 7.5 0 1 1 15 0Z"
        />
      </svg>
    ),
  },
  {
    title: 'AI 需求理解与推荐',
    description: '一句话描述预算、户型和偏好，AI 解析成结构化条件并给出带理由的推荐排序。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-6 w-6" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.26 3.74 18 4.5l-.26-.76a2.25 2.25 0 0 0-1.48-1.48L15.5 2l.76-.26a2.25 2.25 0 0 0 1.48-1.48L18-.5l.26.76a2.25 2.25 0 0 0 1.48 1.48L20.5 2l-.76.26a2.25 2.25 0 0 0-1.48 1.48Z"
        />
      </svg>
    ),
  },
  {
    title: '来源可溯的房源数据',
    description: '每套房源保留采集来源与更新时间，评估结论附带依据，可回查原始数据。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-6 w-6" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M9 12.75 11.25 15 15 9.75m-3-7.036A11.96 11.96 0 0 1 3.6 6c-.086.487-.1.986-.1 1.5 0 5.5 3.5 10.15 8.5 11.75 5-1.6 8.5-6.25 8.5-11.75 0-.514-.014-1.013-.1-1.5a11.96 11.96 0 0 1-8.4-3.286Z"
        />
      </svg>
    ),
  },
]

const WORKFLOW_STEPS = [
  {
    title: '选城市与模式',
    description: '选择目标城市，决定用平台采集房源检索，还是只分析自己导入的候选房源。',
  },
  {
    title: '地图 + 自然语言搜索',
    description: '点选地图位置或直接说出需求，系统解析预算、户型、通勤半径等条件并检索。',
  },
  {
    title: 'AI 推荐与深度分析',
    description: '获得带理由的推荐排序，逐套查看价值、环境、通勤评估与来源可查的深度分析。',
  },
]

/**
 * 首页：Hero + 能力三卡 + 工作流程 + 热门城市 + 邮箱订阅。
 */
function HomePage() {
  const navigate = useNavigate()

  const [heroBadge, setHeroBadge] = useState(null)
  const [hotCities, setHotCities] = useState([])
  const [citiesFailed, setCitiesFailed] = useState(false)

  const [subscribeEmail, setSubscribeEmail] = useState('')
  const [subscribeState, setSubscribeState] = useState({ status: 'idle', message: '' })

  useEffect(() => {
    let cancelled = false
    cityService
      .getHomeConfig()
      .then((data) => {
        if (!cancelled && data?.heroBadge?.text) setHeroBadge(data.heroBadge)
      })
      .catch(() => {
        /* 配置加载失败时使用默认文案，静默降级 */
      })
    cityService
      .getCities({ limit: 8 })
      .then((data) => {
        if (cancelled) return
        const list = Array.isArray(data?.cities) ? data.cities : Array.isArray(data?.items) ? data.items : []
        const enabled = list.filter((city) => city?.enabled ?? city?.status === 'available').slice(0, 8)
        setHotCities(enabled)
        setCitiesFailed(enabled.length === 0)
      })
      .catch(() => {
        if (!cancelled) setCitiesFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const handleSubscribe = useCallback(
    async (event) => {
      event.preventDefault()
      const email = subscribeEmail.trim()
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        setSubscribeState({ status: 'error', message: '请输入有效的邮箱地址' })
        return
      }
      setSubscribeState({ status: 'loading', message: '' })
      try {
        await subscriptionService.subscribe(email)
        setSubscribeState({ status: 'success', message: '订阅成功，新城市上线时会第一时间通知你。' })
        setSubscribeEmail('')
      } catch (err) {
        setSubscribeState({ status: 'error', message: err.message || '订阅失败，请稍后重试。' })
      }
    },
    [subscribeEmail],
  )

  const scrollToWorkflow = () => {
    document.getElementById('workflow')?.scrollIntoView({ behavior: 'smooth' })
  }

  return (
    <SiteLayout>
      {/* Hero */}
      <section className="relative overflow-hidden">
        <div
          className="pointer-events-none absolute -top-20 right-[12%] h-80 w-80 rounded-full bg-brand-400/20 blur-3xl"
          aria-hidden="true"
        />
        <div
          className="pointer-events-none absolute bottom-0 left-[8%] h-64 w-64 rounded-full bg-sky-300/25 blur-3xl"
          aria-hidden="true"
        />
        <div className="relative mx-auto flex max-w-7xl flex-col items-center px-4 py-20 text-center sm:px-6 sm:py-28">
          <div className="animate-fade-up">
            {heroBadge?.text && (
              <Badge tone="brand" className="mb-5 px-3 py-1">
                <span aria-hidden="true">✦</span>
                {heroBadge.text}
              </Badge>
            )}
            <h1 className="mx-auto max-w-3xl text-4xl font-semibold leading-tight tracking-tight text-ink-900 sm:text-5xl">
              用 AI 把租房决策
              <span className="text-brand-600">讲清楚</span>
            </h1>
            <p className="mx-auto mt-5 max-w-xl text-base leading-7 text-ink-500">
              在地图上圈定生活范围，用一句话说清需求，获得来源可查、理由充分的房源推荐与深度分析。
            </p>
            <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
              <Button size="lg" onClick={() => navigate('/cities')}>
                进入城市工作台
              </Button>
              <Button variant="secondary" size="lg" onClick={scrollToWorkflow}>
                了解工作流程
              </Button>
            </div>
          </div>
        </div>
      </section>

      {/* 能力三卡 */}
      <section className="mx-auto max-w-7xl px-4 pb-4 sm:px-6" aria-label="核心能力">
        <div className="grid gap-4 md:grid-cols-3">
          {FEATURES.map((feature) => (
            <div
              key={feature.title}
              className="rounded-2xl bg-white p-6 shadow-card ring-1 ring-ink-100/60 transition duration-200 hover:-translate-y-0.5 hover:shadow-float"
            >
              <span className="inline-flex h-11 w-11 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                {feature.icon}
              </span>
              <h2 className="mt-4 text-sm font-semibold text-ink-900">{feature.title}</h2>
              <p className="mt-2 text-sm leading-6 text-ink-500">{feature.description}</p>
            </div>
          ))}
        </div>
      </section>

      {/* 工作流程 */}
      <section id="workflow" className="mx-auto max-w-7xl scroll-mt-20 px-4 py-16 sm:px-6" aria-label="工作流程">
        <h2 className="text-center text-2xl font-semibold tracking-tight text-ink-900 sm:text-3xl">三步完成一次找房</h2>
        <p className="mt-2 text-center text-sm text-ink-500">从选城市到拿到分析结论，全程在一个工作台里完成。</p>
        <ol className="mt-10 grid gap-6 md:grid-cols-3">
          {WORKFLOW_STEPS.map((step, index) => (
            <li key={step.title} className="relative">
              {index < WORKFLOW_STEPS.length - 1 && (
                <span
                  className="absolute left-full top-6 hidden h-px w-6 -translate-x-3 bg-ink-200 md:block"
                  aria-hidden="true"
                />
              )}
              <div className="h-full rounded-2xl bg-white p-6 shadow-card ring-1 ring-ink-100/60">
                <span className="flex h-9 w-9 items-center justify-center rounded-full bg-brand-600 text-sm font-semibold text-white">
                  {index + 1}
                </span>
                <h3 className="mt-4 text-sm font-semibold text-ink-900">{step.title}</h3>
                <p className="mt-2 text-sm leading-6 text-ink-500">{step.description}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>

      {/* 热门城市 */}
      {!citiesFailed && hotCities.length > 0 && (
        <section className="mx-auto max-w-7xl px-4 pb-16 sm:px-6" aria-label="热门城市">
          <div className="flex items-end justify-between">
            <div>
              <h2 className="text-2xl font-semibold tracking-tight text-ink-900">热门城市</h2>
              <p className="mt-1.5 text-sm text-ink-500">已开通 AI 找房工作台的城市，点击直接进入。</p>
            </div>
            <Link to="/cities" className="text-sm font-medium text-brand-600 transition hover:text-brand-700">
              全部城市 →
            </Link>
          </div>
          <ul className="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
            {hotCities.map((city) => (
              <li key={city.name}>
                <Link
                  to={`/city/${encodeURIComponent(city.name)}`}
                  className="block rounded-2xl bg-white p-4 shadow-card ring-1 ring-ink-100/60 transition duration-200 hover:-translate-y-0.5 hover:shadow-float hover:ring-brand-200"
                  aria-label={`进入${city.name}工作台`}
                >
                  <p className="text-base font-semibold text-ink-900">{city.name}</p>
                  <p className="mt-1 truncate text-xs uppercase tracking-wide text-ink-400">
                    {city.nameEn || city.en || city.province || '—'}
                  </p>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* 订阅条 */}
      <section className="mx-auto max-w-7xl px-4 pb-20 sm:px-6" aria-label="邮箱订阅">
        <div className="relative overflow-hidden rounded-3xl bg-ink-950 px-6 py-10 sm:px-10">
          <div
            className="pointer-events-none absolute -right-10 -top-16 h-56 w-56 rounded-full bg-brand-500/30 blur-3xl"
            aria-hidden="true"
          />
          <div className="relative flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
            <div>
              <h2 className="text-xl font-semibold text-white">订阅城市上线通知</h2>
              <p className="mt-1.5 text-sm text-ink-300">新城市开通、数据源扩充时第一时间收到邮件。</p>
            </div>
            <form className="flex w-full max-w-md flex-col gap-2" onSubmit={handleSubscribe} noValidate>
              <div className="flex gap-2">
                <label htmlFor="subscribe-email" className="sr-only">
                  邮箱地址
                </label>
                <input
                  id="subscribe-email"
                  type="email"
                  value={subscribeEmail}
                  onChange={(event) => setSubscribeEmail(event.target.value)}
                  placeholder="you@example.com"
                  className="h-11 flex-1 rounded-full border-0 bg-white/10 px-4 text-sm text-white ring-1 ring-inset ring-white/20 transition placeholder:text-ink-400 focus:bg-white/15 focus:ring-2 focus:ring-brand-400"
                />
                <Button type="submit" loading={subscribeState.status === 'loading'} aria-label="订阅">
                  订阅
                </Button>
              </div>
              {subscribeState.message && (
                <p
                  className={[
                    'text-xs',
                    subscribeState.status === 'success' ? 'text-emerald-400' : 'text-rose-400',
                  ].join(' ')}
                  role="status"
                >
                  {subscribeState.message}
                </p>
              )}
            </form>
          </div>
        </div>
      </section>
    </SiteLayout>
  )
}

export default HomePage
