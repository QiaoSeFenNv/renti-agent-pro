#!/usr/bin/env node
/**
 * 贝壳 / 链家「上海租房」列表抓取器（Node + puppeteer-core）。
 *
 * 贝壳找房与链家共用同一套前端 DOM（.content__list--item），因此一个解析器同时支持两家，
 * 仅入口 URL 与 provider 不同。用本机已安装的 Chrome 无头渲染（比纯 HTTP 更抗间歇性反爬），
 * 出站走代理 127.0.0.1:7897，翻页之间随机延时。
 *
 * 列表页只携带布尔「官方核验」旗标（content__item__tag--gov_certification），真正的核验编号
 * 在详情页——按既定设计交给后端异步核验器（P2）去详情页取编号再反查官方平台，这里只抓列表页。
 *
 * 输出：向 stdout 打印 {"ok":true,"provider":..,"items":[...]} JSON，字段为 snake_case，
 * 供 BeikeShanghaiPlugin / LianjiaShanghaiPlugin 进程内 importRows 导入候选审核流。
 *
 * 用法：
 *   node scripts/beike_ingest.cjs --provider beike --pages 3 --output items
 *   node scripts/beike_ingest.cjs --provider lianjia --districts pudong,minhang,xuhui --pages 2
 */
'use strict'

const path = require('path')
const fs = require('fs')

// --------------------------------------------------------------------------- puppeteer 解析

/** 优先本目录，其次前端 node_modules（run-ui-check.cjs 已安装 puppeteer-core 25.x）。 */
function resolvePuppeteer() {
  const explicit = (process.env.PUPPETEER_MODULE || '').trim()
  const candidates = [
    explicit,
    'puppeteer-core',
    path.resolve(__dirname, '..', '..', 'renti-agent-front', 'node_modules', 'puppeteer-core'),
    path.resolve(__dirname, '..', 'node_modules', 'puppeteer-core'),
  ].filter(Boolean)
  for (const candidate of candidates) {
    try {
      return require(candidate)
    } catch (err) {
      /* 尝试下一个候选路径 */
    }
  }
  throw new Error('找不到 puppeteer-core：请在 renti-agent-front 安装，或设置环境变量 PUPPETEER_MODULE')
}

function resolveChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ].filter(Boolean)
  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) return candidate
  }
  return candidates[1]
}

// --------------------------------------------------------------------------- provider 配置

const PROVIDERS = {
  beike: { provider: 'beike', source: 'beike_shanghai', base: 'https://sh.zu.ke.com/zufang/' },
  lianjia: { provider: 'lianjia', source: 'lianjia_shanghai', base: 'https://sh.lianjia.com/zufang/' },
}

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const log = (msg) => process.stderr.write(msg + '\n')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const jitter = (min, max) => Math.floor(min + Math.random() * (max - min))

/** /pg{n}/ 分页（对齐链家/贝壳 URL 规则）。 */
function pageUrl(base, page) {
  const clean = base.split('?')[0].replace(/\/+$/, '').replace(/\/pg\d+$/, '')
  return page <= 1 ? clean + '/' : clean + '/pg' + page + '/'
}

/** 区域入口：/zufang/{districtPinyin}/（用于缓解区域倒挂，多区轮抓）。 */
function districtUrl(base, district) {
  const root = base.split('/zufang')[0]
  return root + '/zufang/' + String(district).trim().replace(/\/+$/, '') + '/'
}

// --------------------------------------------------------------------------- 页面内提取

/**
 * 在浏览器上下文中解析当前列表页的房源卡片。返回 snake_case 行数组。
 * 注意：此函数被序列化到浏览器执行，不能引用外部作用域变量。
 */
function extractCardsInPage() {
  const SH_DISTRICTS = [
    '浦东', '闵行', '徐汇', '静安', '黄浦', '长宁', '普陀', '虹口', '杨浦',
    '宝山', '嘉定', '松江', '青浦', '奉贤', '金山', '崇明',
  ]
  const clean = (t) => (t || '').replace(/\s+/g, ' ').trim()
  const intFrom = (t) => {
    const m = String(t || '').match(/\d+(?:\.\d+)?/)
    return m ? Math.round(parseFloat(m[0])) : 0
  }

  const cards = Array.from(document.querySelectorAll('.content__list--item'))
  const rows = []
  for (const card of cards) {
    const text = clean(card.textContent)
    if (/已出租|已下架|已成交/.test(text)) continue

    const externalId = card.getAttribute('data-house_code') || ''
    const titleEl = card.querySelector('.content__list--item--title a')
    const title = clean(titleEl && titleEl.textContent)
    const href = titleEl ? titleEl.getAttribute('href') || '' : ''
    if (!title || !href) continue

    // 地址：des 段落里前若干个 <a> 依次是 区 / 板块 / 小区
    const des = card.querySelector('.content__list--item--des')
    const anchors = des ? Array.from(des.querySelectorAll('a')).map((a) => clean(a.textContent)) : []
    let district = anchors[0] || ''
    let businessArea = anchors[1] || ''
    let community = anchors[2] || ''
    if (anchors.length === 2) {
      businessArea = ''
      community = anchors[1] || ''
    }
    if (!SH_DISTRICTS.includes(district)) {
      const hit = SH_DISTRICTS.find((d) => text.includes(d))
      if (hit) district = hit
    }

    const desText = des ? clean(des.textContent) : text
    let layout = ''
    let m = desText.match(/(\d+)\s*室\s*(\d+)\s*厅\s*(\d+)\s*卫/)
    if (m) layout = m[1] + '室' + m[2] + '厅' + m[3] + '卫'
    else if ((m = desText.match(/(\d+)\s*室\s*(\d+)\s*厅/))) layout = m[1] + '室' + m[2] + '厅'
    else if ((m = desText.match(/(\d+)\s*室/))) layout = m[1] + '室'

    const areaMatch = desText.match(/([\d.]+)\s*(?:㎡|平米|m²)/)
    const areaSqm = areaMatch ? Math.round(parseFloat(areaMatch[1])) : 0

    const priceEl = card.querySelector('.content__list--item-price em')
    const rentPrice = intFrom(priceEl && priceEl.textContent)

    const rentType = title.includes('合租') ? '合租' : '整租'

    // 标签 + 官方核验旗标
    const tags = []
    let govCertified = false
    for (const tagEl of card.querySelectorAll('[class*="content__item__tag"]')) {
      const cls = tagEl.getAttribute('class') || ''
      const label = clean(tagEl.textContent)
      if (cls.includes('gov_certification')) govCertified = true
      if (label && label.length <= 12 && !tags.includes(label)) tags.push(label)
    }

    // 图片：优先 data-src（懒加载），去掉占位图
    const images = []
    for (const img of card.querySelectorAll('img')) {
      const src = img.getAttribute('data-src') || img.getAttribute('src') || ''
      if (src && /^https?:/.test(src) && !/default\/250-182|_v=/.test(src) && !images.includes(src)) {
        images.push(src)
      }
      if (images.length >= 8) break
    }

    rows.push({
      external_id: externalId,
      title: title,
      district: district,
      business_area: businessArea,
      community: community,
      rent_price: rentPrice,
      layout: layout,
      area_sqm: areaSqm,
      rent_type: rentType,
      nearest_metro: '待补充',
      metro_distance_m: 999,
      tags: tags.slice(0, 8),
      href: href,
      images: images,
      gov_certified: govCertified,
    })
  }
  return rows
}

// --------------------------------------------------------------------------- 抓取主流程

async function crawl(cfg, entryUrls, maxPages, limit, noProxy) {
  const puppeteer = resolvePuppeteer()
  const chrome = resolveChrome()
  const proxy = noProxy ? '' : (process.env.CRAWLER_PROXY || 'http://127.0.0.1:7897')

  const args = [
    '--window-size=1440,900',
    '--disable-gpu',
    '--no-first-run',
    '--no-sandbox',
    '--disable-blink-features=AutomationControlled',
    '--lang=zh-CN',
  ]
  if (proxy) args.push('--proxy-server=' + proxy)

  log('[beike_ingest] chrome=' + chrome + ' proxy=' + (proxy || '(none)'))
  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: 'new',
    args: args,
    defaultViewport: { width: 1440, height: 900 },
  })

  const seen = new Set()
  const items = []
  const errors = []
  try {
    const page = await browser.newPage()
    await page.setUserAgent(UA)
    await page.setExtraHTTPHeaders({ 'Accept-Language': 'zh-CN,zh;q=0.9' })
    // 拦截图片/字体/媒体资源加速（我们只读 <img> 属性，不需要真正下载）
    await page.setRequestInterception(true)
    page.on('request', (req) => {
      const type = req.resourceType()
      if (type === 'image' || type === 'font' || type === 'media') req.abort()
      else req.continue()
    })

    for (const entry of entryUrls) {
      for (let p = 1; p <= maxPages; p++) {
        const url = pageUrl(entry, p)
        try {
          await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 })
          await page
            .waitForSelector('.content__list--item', { timeout: 15000 })
            .catch(() => {})
          const rows = await page.evaluate(extractCardsInPage)
          if (rows.length === 0) {
            const diag = await page.evaluate(() => ({ title: document.title, bodyLen: document.body ? document.body.innerText.length : 0 }))
            log('[beike_ingest] 0 卡片 @ ' + url + ' title="' + diag.title + '" bodyLen=' + diag.bodyLen)
          }
          let added = 0
          for (const row of rows) {
            const key = row.external_id || row.href
            if (!key || seen.has(key)) continue
            seen.add(key)
            row.url = row.href.startsWith('http')
              ? row.href
              : entry.split('/zufang')[0] + row.href
            delete row.href
            row.image = row.images.length ? row.images[0] : ''
            row.provider = cfg.provider
            row.source = cfg.source
            items.push(row)
            added++
            if (items.length >= limit) break
          }
          log('[beike_ingest] ' + url + ' → +' + added + ' (累计 ' + items.length + ')')
          if (added === 0) break // 空页/被拦，停止该入口翻页
          if (items.length >= limit) break
        } catch (err) {
          errors.push(url + ': ' + String(err.message || err).slice(0, 120))
          log('[beike_ingest] 抓取失败 ' + url + '：' + err.message)
          break
        }
        await sleep(jitter(1200, 2800))
      }
      if (items.length >= limit) break
      await sleep(jitter(1500, 3200))
    }
  } finally {
    await browser.close().catch(() => {})
  }
  return { items, errors }
}

// --------------------------------------------------------------------------- CLI

function parseArgs(argv) {
  const out = { provider: 'beike', pages: 2, limit: 120, output: 'items', districts: '', url: '', noProxy: false }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--provider') out.provider = argv[++i]
    else if (a === '--pages') out.pages = parseInt(argv[++i], 10)
    else if (a === '--limit') out.limit = parseInt(argv[++i], 10)
    else if (a === '--output') out.output = argv[++i]
    else if (a === '--districts') out.districts = argv[++i]
    else if (a === '--url') out.url = argv[++i]
    else if (a === '--no-proxy') out.noProxy = true
  }
  return out
}

async function main() {
  const opts = parseArgs(process.argv)
  const cfg = PROVIDERS[opts.provider]
  if (!cfg) throw new Error('未知 provider：' + opts.provider + '（支持 beike / lianjia）')

  const maxPages = Math.max(1, Math.min(10, opts.pages || 2))
  const limit = Math.max(1, Math.min(500, opts.limit || 120))

  let entryUrls
  if (opts.url) {
    entryUrls = [opts.url]
  } else if (opts.districts) {
    const list = opts.districts.split(/[,，\s]+/).filter(Boolean)
    entryUrls = list.map((d) => districtUrl(cfg.base, d))
  } else {
    entryUrls = [cfg.base]
  }

  log('[beike_ingest] provider=' + cfg.provider + ' 入口=' + entryUrls.length + ' pages≤' + maxPages + ' limit=' + limit)
  const { items, errors } = await crawl(cfg, entryUrls, maxPages, limit, opts.noProxy)

  if (opts.output === 'items') {
    process.stdout.write(
      JSON.stringify({ ok: items.length > 0, provider: cfg.provider, items: items, errors: errors }) + '\n'
    )
  } else {
    log('[beike_ingest] 完成：' + items.length + ' 条，错误 ' + errors.length + ' 处')
  }
}

main().catch((err) => {
  process.stdout.write(JSON.stringify({ ok: false, items: [], error: String(err.message || err) }) + '\n')
  log('[beike_ingest] 致命错误：' + (err.stack || err))
  process.exit(1)
})
