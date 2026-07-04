import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import Badge from '../components/ui/Badge.jsx'
import Button from '../components/ui/Button.jsx'
import SiteLayout from '../layouts/SiteLayout.jsx'
import useReveal from '../hooks/useReveal.js'
import useTypewriter from '../hooks/useTypewriter.js'
import { cityService, subscriptionService } from '../services/cityService.js'

/** 打字机轮播的示例需求：即产品最核心的自然语言查询能力 */
const DEMO_QUERIES = [
  '人民广场地铁站 1km 内，预算 6500，一居室',
  '陆家嘴通勤 30 分钟以内，两居室，要有电梯',
  '徐家汇附近 8000 以内整租，离地铁近一点',
  '张江高科上班，预算 5000，合租也可以',
]

const FEATURES = [
  {
    title: '地图圈定',
    description: '点选位置、设定半径，只看真正到得了的房源，距离与通勤一目了然。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm4.5 0c0 5.25-7.5 11.25-7.5 11.25S4.5 15.75 4.5 10.5a7.5 7.5 0 1 1 15 0Z"
        />
      </svg>
    ),
  },
  {
    title: 'AI 需求理解',
    description: '一句话说清预算、户型和偏好，解析为结构化条件，推荐附带理由。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5" aria-hidden="true">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09Z"
        />
      </svg>
    ),
  },
  {
    title: '来源可溯',
    description: '每套房源保留采集来源与更新时间，结论有依据，可回查原始数据。',
    icon: (
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5" aria-hidden="true">
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
  { no: '01', title: '选城市与模式', description: '平台房源检索，或只分析自己导入的候选。' },
  { no: '02', title: '地图 + 一句话', description: '点选位置或说出需求，解析预算、户型与半径。' },
  { no: '03', title: '推荐与深度分析', description: '带理由的排序，逐套查看评估与数据来源。' },
]

/**
 * 首页：夜景 Hero（打字机命令条）+ 能力三卡 + 工作流程 + 热门城市 + 订阅。
 */
function HomePage() {
  const navigate = useNavigate()
  const revealRef = useReveal()
  const typed = useTypewriter(DEMO_QUERIES)

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
      <div ref={revealRef}>
        {/* ---------- Hero：城市夜景 + 打字机命令条 ---------- */}
        <section className="relative -mt-16 overflow-hidden">
          {/* 背景：夜景照片 → 渐晕压暗 → 网格 → 光斑 */}
          <img
            src="/img/skyline-dusk.jpg"
            alt=""
            aria-hidden="true"
            className="absolute inset-0 h-full w-full object-cover object-[center_38%]"
          />
          <div className="absolute inset-0 bg-gradient-to-b from-ink-50/70 via-ink-50/55 to-ink-50" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_70%_55%_at_50%_40%,transparent_20%,rgba(8,9,15,0.55)_100%)]" />
          <div className="bg-grid bg-grid-fade absolute inset-0" aria-hidden="true" />
          <div
            className="pointer-events-none absolute -left-24 top-1/3 h-96 w-96 rounded-full bg-brand-500/20 blur-3xl animate-float-slow"
            aria-hidden="true"
          />
          <div
            className="pointer-events-none absolute -right-16 top-1/4 h-80 w-80 rounded-full bg-cyan-400/10 blur-3xl animate-float-slow [animation-delay:-4s]"
            aria-hidden="true"
          />

          <div className="relative mx-auto flex min-h-[92vh] max-w-7xl flex-col items-center justify-center px-4 pb-24 pt-32 text-center sm:px-6">
            {heroBadge?.text && (
              <div className="animate-fade-up">
                <Badge tone="brand" className="mb-6 px-3.5 py-1.5 backdrop-blur">
                  <span className="mr-0.5 inline-block h-1.5 w-1.5 rounded-full bg-cyan-300 animate-pulse-glow" aria-hidden="true" />
                  {heroBadge.text}
                </Badge>
              </div>
            )}

            <h1
              className="mx-auto max-w-4xl font-display text-4xl font-bold leading-[1.15] tracking-tight text-ink-950 animate-fade-up sm:text-6xl"
              style={{ animationDelay: '80ms' }}
            >
              用 AI 把租房决策
              <span className="text-gradient">讲清楚</span>
            </h1>

            <p
              className="mx-auto mt-6 max-w-xl text-base leading-7 text-ink-600 animate-fade-up"
              style={{ animationDelay: '160ms' }}
            >
              在地图上圈定生活范围，用一句话说清需求，
              <br className="hidden sm:block" />
              获得来源可查、理由充分的房源推荐与深度分析。
            </p>

            {/* 打字机命令条：产品核心交互的预览，点击即进入 */}
            <button
              type="button"
              onClick={() => navigate('/cities')}
              aria-label="开始搜索：选择城市"
              className="glass-strong group mt-10 flex w-full max-w-2xl items-center gap-3 rounded-2xl px-5 py-4 text-left shadow-float transition duration-300 hover:ring-brand-400/40 hover:shadow-glow-lg animate-fade-up"
              style={{ animationDelay: '240ms' }}
            >
              <svg
                viewBox="0 0 20 20"
                fill="currentColor"
                className="h-5 w-5 shrink-0 text-brand-400"
                aria-hidden="true"
              >
                <path
                  fillRule="evenodd"
                  d="M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.45 4.4l3.07 3.08a.75.75 0 1 1-1.06 1.06l-3.07-3.07A7 7 0 0 1 2 9Z"
                  clipRule="evenodd"
                />
              </svg>
              <span className="type-caret min-w-0 flex-1 truncate font-mono text-sm text-ink-800 sm:text-base">
                {typed}
              </span>
              <span className="hidden shrink-0 items-center gap-1 rounded-full bg-brand-gradient px-3.5 py-1.5 text-xs font-semibold text-white shadow-glow transition group-hover:brightness-110 sm:inline-flex">
                开始找房
                <svg viewBox="0 0 20 20" fill="currentColor" className="h-3.5 w-3.5" aria-hidden="true">
                  <path
                    fillRule="evenodd"
                    d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z"
                    clipRule="evenodd"
                  />
                </svg>
              </span>
            </button>

            <div
              className="mt-8 flex flex-wrap items-center justify-center gap-3 animate-fade-up"
              style={{ animationDelay: '320ms' }}
            >
              <Button size="lg" onClick={() => navigate('/cities')}>
                进入城市工作台
              </Button>
              <Button variant="secondary" size="lg" onClick={scrollToWorkflow}>
                了解工作流程
              </Button>
            </div>

            {/* 滚动提示 */}
            <div
              className="pointer-events-none absolute bottom-8 left-1/2 -translate-x-1/2 text-ink-400 animate-fade-in"
              style={{ animationDelay: '900ms' }}
              aria-hidden="true"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="h-5 w-5 animate-bounce">
                <path strokeLinecap="round" strokeLinejoin="round" d="M19.5 8.25 12 15.75 4.5 8.25" />
              </svg>
            </div>
          </div>
        </section>

        {/* ---------- 能力三卡 ---------- */}
        <section className="mx-auto max-w-7xl px-4 pt-4 sm:px-6" aria-label="核心能力">
          <div className="grid gap-4 md:grid-cols-3">
            {FEATURES.map((feature, index) => (
              <div
                key={feature.title}
                className="glass reveal group relative overflow-hidden rounded-2xl p-6 transition duration-300 hover:-translate-y-1 hover:shadow-glow hover:ring-white/20"
                style={{ transitionDelay: `${index * 70}ms` }}
              >
                <div
                  className="pointer-events-none absolute -right-10 -top-10 h-32 w-32 rounded-full bg-brand-500/10 blur-2xl opacity-0 transition duration-500 group-hover:opacity-100"
                  aria-hidden="true"
                />
                <span className="relative inline-flex h-11 w-11 items-center justify-center rounded-xl bg-brand-gradient-soft text-brand-300 ring-1 ring-brand-400/25">
                  {feature.icon}
                </span>
                <h2 className="relative mt-4 text-sm font-semibold text-ink-900">{feature.title}</h2>
                <p className="relative mt-2 text-sm leading-6 text-ink-500">{feature.description}</p>
              </div>
            ))}
          </div>
        </section>

        {/* ---------- 工作流程 ---------- */}
        <section id="workflow" className="mx-auto max-w-7xl scroll-mt-24 px-4 py-24 sm:px-6" aria-label="工作流程">
          <div className="reveal">
            <p className="text-center font-mono text-xs uppercase tracking-[0.3em] text-brand-400">Workflow</p>
            <h2 className="mt-3 text-center font-display text-2xl font-semibold tracking-tight text-ink-900 sm:text-3xl">
              三步完成一次找房
            </h2>
          </div>
          <ol className="relative mt-14 grid gap-10 md:grid-cols-3 md:gap-6">
            {/* 连接线 */}
            <div
              className="pointer-events-none absolute left-[16.6%] right-[16.6%] top-5 hidden h-px bg-gradient-to-r from-brand-500/50 via-cyan-400/40 to-brand-500/50 md:block"
              aria-hidden="true"
            />
            {WORKFLOW_STEPS.map((step, index) => (
              <li key={step.no} className="reveal relative text-center md:px-4" style={{ transitionDelay: `${index * 90}ms` }}>
                <span className="relative inline-flex h-10 w-10 items-center justify-center rounded-full bg-surface font-mono text-sm font-semibold text-brand-300 ring-1 ring-brand-400/40 shadow-glow">
                  {step.no}
                </span>
                <h3 className="mt-4 text-sm font-semibold text-ink-900">{step.title}</h3>
                <p className="mx-auto mt-2 max-w-[16rem] text-sm leading-6 text-ink-500">{step.description}</p>
              </li>
            ))}
          </ol>
        </section>

        {/* ---------- 热门城市 ---------- */}
        {!citiesFailed && hotCities.length > 0 && (
          <section className="mx-auto max-w-7xl px-4 pb-24 sm:px-6" aria-label="热门城市">
            <div className="reveal flex items-end justify-between">
              <div>
                <p className="font-mono text-xs uppercase tracking-[0.3em] text-brand-400">Cities</p>
                <h2 className="mt-3 font-display text-2xl font-semibold tracking-tight text-ink-900">热门城市</h2>
              </div>
              <Link
                to="/cities"
                className="group inline-flex items-center gap-1 text-sm font-medium text-brand-300 transition hover:text-white"
              >
                全部城市
                <svg viewBox="0 0 20 20" fill="currentColor" className="h-4 w-4 transition group-hover:translate-x-0.5" aria-hidden="true">
                  <path
                    fillRule="evenodd"
                    d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z"
                    clipRule="evenodd"
                  />
                </svg>
              </Link>
            </div>
            <ul className="mt-8 grid grid-cols-2 gap-3 sm:grid-cols-4">
              {hotCities.map((city, index) => (
                <li key={city.name} className="reveal" style={{ transitionDelay: `${index * 45}ms` }}>
                  <Link
                    to={`/city/${encodeURIComponent(city.name)}`}
                    className="glass group block rounded-2xl p-4 transition duration-300 hover:-translate-y-1 hover:shadow-glow hover:ring-brand-400/40"
                    aria-label={`进入${city.name}工作台`}
                  >
                    <div className="flex items-center justify-between">
                      <p className="text-base font-semibold text-ink-900 transition group-hover:text-white">{city.name}</p>
                      <svg
                        viewBox="0 0 20 20"
                        fill="currentColor"
                        className="h-4 w-4 text-ink-300 opacity-0 transition duration-300 group-hover:translate-x-0.5 group-hover:text-brand-300 group-hover:opacity-100"
                        aria-hidden="true"
                      >
                        <path
                          fillRule="evenodd"
                          d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z"
                          clipRule="evenodd"
                        />
                      </svg>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs uppercase tracking-wide text-ink-400">
                      {city.nameEn || city.en || city.province || '—'}
                    </p>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}

        {/* ---------- 订阅条 ---------- */}
        <section className="mx-auto max-w-7xl px-4 pb-24 sm:px-6" aria-label="邮箱订阅">
          <div className="glass reveal relative overflow-hidden rounded-3xl px-6 py-10 sm:px-10">
            <div
              className="pointer-events-none absolute -right-10 -top-16 h-56 w-56 rounded-full bg-brand-500/20 blur-3xl animate-float-slow"
              aria-hidden="true"
            />
            <div
              className="pointer-events-none absolute -bottom-20 -left-10 h-48 w-48 rounded-full bg-cyan-400/10 blur-3xl"
              aria-hidden="true"
            />
            <div className="relative flex flex-col gap-6 md:flex-row md:items-center md:justify-between">
              <div>
                <h2 className="font-display text-xl font-semibold text-ink-950">订阅城市上线通知</h2>
                <p className="mt-1.5 text-sm text-ink-500">新城市开通、数据源扩充时第一时间收到邮件。</p>
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
                    className="h-11 flex-1 rounded-full border-0 bg-black/30 px-4 font-mono text-sm text-ink-900 ring-1 ring-inset ring-white/10 transition placeholder:text-ink-300 focus:bg-black/45 focus:ring-2 focus:ring-brand-500/80"
                  />
                  <Button type="submit" loading={subscribeState.status === 'loading'} aria-label="订阅">
                    订阅
                  </Button>
                </div>
                {subscribeState.message && (
                  <p
                    className={[
                      'text-xs',
                      subscribeState.status === 'success' ? 'text-emerald-700' : 'text-rose-700',
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
      </div>
    </SiteLayout>
  )
}

export default HomePage
