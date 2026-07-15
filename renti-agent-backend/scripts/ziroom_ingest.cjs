#!/usr/bin/env node
/**
 * 自如「上海租房」列表抓取器（Node + puppeteer-core）。
 *
 * 自如为长租公寓自持自营——房源均为真实在租房源、无中介钓鱼房，真实性最高。列表页 SPA 需无头渲染。
 * 卡片 .item 携带 data-inv-no（房源号）/data-bedroom（居室数），小区/面积/楼层/朝向/地铁均为明文；
 * 唯独价格用雪碧图字体加密（.num 背景图偏移），本脚本按「偏移 → 单元格索引 → 数字」解码，
 * 映射表见 ZIROOM_PRICE_MAP（按雪碧图 PNG hash 建表，未知雪碧图则价格留空、候选滞留人工审核）。
 *
 * 输出：stdout 打印 {"ok":true,"provider":"ziroom","items":[...]} JSON（snake_case），
 * 供 ZiroomShanghaiPlugin 进程内 importRows 导入候选审核流。
 *
 * 用法：node scripts/ziroom_ingest.cjs --pages 3 --limit 100 --output items
 */
'use strict'

const path = require('path')
const fs = require('fs')
const { decodeSpriteOrder } = require('./lib/ziroom_price.cjs')

const BASE = 'https://sh.ziroom.com/z/'
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'

const log = (msg) => process.stderr.write(msg + '\n')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const jitter = (min, max) => Math.floor(min + Math.random() * (max - min))

function resolvePuppeteer() {
  const candidates = [
    (process.env.PUPPETEER_MODULE || '').trim(),
    'puppeteer-core',
    path.resolve(__dirname, '..', '..', 'renti-agent-front', 'node_modules', 'puppeteer-core'),
  ].filter(Boolean)
  for (const c of candidates) {
    try {
      return require(c)
    } catch (err) {
      /* next */
    }
  }
  throw new Error('找不到 puppeteer-core')
}

function resolveChrome() {
  const candidates = [
    process.env.CHROME_BIN,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  ].filter(Boolean)
  for (const c of candidates) {
    if (fs.existsSync(c)) return c
  }
  return candidates[1]
}

function pageUrl(page) {
  return page <= 1 ? BASE : BASE.replace(/\/+$/, '') + '/z/p' + page + '/'
}

/** 在浏览器上下文提取自如卡片（含价格雪碧图偏移原始数据，供外部解码）。 */
function extractZiroomCards() {
  const SH_DISTRICTS = [
    '浦东', '闵行', '徐汇', '静安', '黄浦', '长宁', '普陀', '虹口', '杨浦',
    '宝山', '嘉定', '松江', '青浦', '奉贤', '金山', '崇明',
  ]
  const clean = (t) => (t || '').replace(/\s+/g, ' ').trim()
  const rows = []
  for (const card of document.querySelectorAll('.item')) {
    const externalId = card.getAttribute('data-inv-no') || ''
    const anchor = card.querySelector('a[href*="/x/"]')
    if (!externalId || !anchor) continue
    let href = anchor.getAttribute('href') || ''
    if (href.startsWith('//')) href = 'https:' + href

    const titleEl = card.querySelector('.info-box .name, .name, h5, a[href*="/x/"] img')
    const desc = clean(card.innerText)

    // 标题/小区
    let title = ''
    const nameEl = card.querySelector('.info-box a, .name')
    if (nameEl) title = clean(nameEl.textContent)
    if (!title && anchor) title = clean(anchor.getAttribute('title') || (anchor.querySelector('img') || {}).alt || '')
    // 小区名：去掉自如标题里的房间后缀（如「鼎信公寓3居+·02卧」→「鼎信公寓」），利于地理编码与跨源去重
    let community = title.replace(/\d+居.*$/, '').replace(/·.*$/, '').replace(/\s+/g, '').trim()
    if (!community) community = title

    // 面积 / 楼层 / 朝向 / 地铁
    let m = desc.match(/([\d.]+)\s*㎡/)
    const areaSqm = m ? Math.round(parseFloat(m[1])) : 0
    const floorMatch = desc.match(/(\d+)\/(\d+)层/)
    const orientation = (desc.match(/朝([东南西北]+)/) || [])[1] || ''
    const metroMatch = desc.match(/距(\d+号线[^\s]+?站)步行约(\d+)米/)
    const nearestMetro = metroMatch ? metroMatch[1] : '待补充'
    const metroDistanceM = metroMatch ? parseInt(metroMatch[2], 10) : 999
    // 商圈/板块：自如列表页不直接给商圈，从最近地铁站名派生（如「9号线松江新城站」→「松江新城」）
    let businessArea = ''
    if (metroMatch) {
      businessArea = metroMatch[1].replace(/^\d+号线(?:支线)?/, '').replace(/站$/, '').trim()
    }

    // 户型：data-bedroom 居室
    const bedroom = card.getAttribute('data-bedroom') || ''
    let layout = ''
    if (/^\d+$/.test(bedroom)) layout = bedroom + '室'
    const layoutText = desc.match(/(\d+)\s*室\s*(\d+)\s*厅/)
    if (layoutText) layout = layoutText[1] + '室' + layoutText[2] + '厅'
    const rentType = /合租|次卧|主卧|单间/.test(desc) ? '合租' : '整租'

    // 区域：从标题/描述中猜行政区
    let district = ''
    for (const d of SH_DISTRICTS) {
      if (desc.includes(d)) {
        district = d
        break
      }
    }

    // 价格雪碧图偏移（外部解码）
    const priceCells = []
    let spriteHash = ''
    let spriteUrl = ''
    let bgHeight = 0
    const priceBox = card.querySelector('.price-content, .price')
    if (priceBox) {
      for (const numEl of priceBox.querySelectorAll('.num')) {
        const cs = getComputedStyle(numEl)
        const offset = Math.abs(parseFloat(cs.backgroundPositionX || cs.backgroundPosition) || 0)
        const bgImg = cs.backgroundImage || ''
        if (!spriteUrl) {
          const um = bgImg.match(/url\(["']?([^"')]+)["']?\)/)
          if (um) {
            spriteUrl = um[1].startsWith('//') ? 'https:' + um[1] : um[1]
            const hm = spriteUrl.match(/([0-9a-f]{16,40})\.png/)
            if (hm) spriteHash = hm[1]
          }
          // background-size: "auto 20px" → 取高度用于换算单元格宽度
          const sz = (cs.backgroundSize || '').match(/(\d+(?:\.\d+)?)px/g)
          if (sz && sz.length) bgHeight = parseFloat(sz[sz.length - 1])
        }
        priceCells.push(offset)
      }
    }

    // 图片
    const images = []
    for (const img of card.querySelectorAll('img')) {
      const src = img.getAttribute('data-original') || img.getAttribute('src') || ''
      if (src && /^https?:/.test(src) && !/qr|code/.test(src) && !images.includes(src)) images.push(src)
      if (images.length >= 6) break
    }

    rows.push({
      external_id: externalId,
      title: title || '自如房源',
      district: district,
      business_area: businessArea,
      community: community,
      area_sqm: areaSqm,
      layout: layout,
      rent_type: rentType,
      orientation: orientation,
      floor_desc: floorMatch ? floorMatch[0] : '',
      nearest_metro: nearestMetro,
      metro_distance_m: metroDistanceM,
      href: href,
      images: images,
      price_cells: priceCells,
      sprite_hash: spriteHash,
      sprite_url: spriteUrl,
      bg_height: bgHeight,
    })
  }
  return rows
}

/**
 * 用雪碧图数字顺序把偏移解码为价格。雪碧图原图 300x28（10 格×30px），渲染时按 bgHeight 缩放，
 * 故渲染单元格宽 = 30 * (bgHeight/28)；单元格索引 = round(offset / 渲染单元格宽)。未知顺序返回 0。
 */
function decodePrice(priceCells, order, bgHeight) {
  if (!order || !priceCells || priceCells.length === 0) return 0
  const cellW = 30 * ((bgHeight > 0 ? bgHeight : 20) / 28)
  let digits = ''
  for (const offset of priceCells) {
    const index = Math.round(offset / cellW)
    if (index < 0 || index > 9) return 0
    digits += order[index]
  }
  const price = parseInt(digits, 10)
  return Number.isFinite(price) && price >= 300 && price <= 100000 ? price : 0
}

/** 在页面上下文抓取雪碧图 PNG，返回 base64（走浏览器代理，绕过 Node 代理配置）。 */
async function fetchSpriteBase64(page, url) {
  return page.evaluate(async (u) => {
    const res = await fetch(u)
    const buf = new Uint8Array(await res.arrayBuffer())
    let bin = ''
    for (let i = 0; i < buf.length; i++) bin += String.fromCharCode(buf[i])
    return btoa(bin)
  }, url)
}

async function crawl(maxPages, limit) {
  const puppeteer = resolvePuppeteer()
  const chrome = resolveChrome()
  const proxy = process.env.CRAWLER_PROXY || 'http://127.0.0.1:7897'
  const args = ['--no-sandbox', '--disable-gpu', '--lang=zh-CN', '--disable-blink-features=AutomationControlled']
  if (proxy) args.push('--proxy-server=' + proxy)

  log('[ziroom_ingest] chrome=' + chrome + ' proxy=' + (proxy || '(none)'))
  const browser = await puppeteer.launch({
    executablePath: chrome,
    headless: 'new',
    args: args,
    defaultViewport: { width: 1440, height: 900 },
  })
  const seen = new Set()
  const items = []
  const errors = []
  const spriteOrders = {} // hash → 数字顺序串（本次运行内缓存）
  let unknownSprite = 0
  try {
    const page = await browser.newPage()
    await page.setUserAgent(UA)
    await page.setExtraHTTPHeaders({ 'Accept-Language': 'zh-CN,zh;q=0.9' })
    for (let p = 1; p <= maxPages; p++) {
      const url = pageUrl(p)
      try {
        await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 })
        await page.waitForSelector('.item', { timeout: 15000 }).catch(() => {})
        await sleep(1500) // 等价格雪碧图样式生效
        const rows = await page.evaluate(extractZiroomCards)

        // 解出本页雪碧图顺序（自如每次请求轮换雪碧图，故按 hash 缓存、按需下载解码）
        for (const row of rows) {
          if (row.sprite_hash && row.sprite_url && !(row.sprite_hash in spriteOrders)) {
            try {
              const b64 = await fetchSpriteBase64(page, row.sprite_url)
              spriteOrders[row.sprite_hash] = decodeSpriteOrder(Buffer.from(b64, 'base64'))
              log('[ziroom_ingest] 雪碧图 ' + row.sprite_hash.slice(0, 8) + ' → ' + spriteOrders[row.sprite_hash])
            } catch (err) {
              spriteOrders[row.sprite_hash] = ''
              log('[ziroom_ingest] 雪碧图解码失败 ' + row.sprite_hash.slice(0, 8) + '：' + err.message)
            }
          }
        }

        let added = 0
        for (const row of rows) {
          if (!row.external_id || seen.has(row.external_id)) continue
          seen.add(row.external_id)
          const order = spriteOrders[row.sprite_hash]
          const price = decodePrice(row.price_cells, order, row.bg_height)
          if (price === 0) unknownSprite++
          row.rent_price = price
          delete row.price_cells
          delete row.sprite_url
          delete row.bg_height
          row.url = row.href
          delete row.href
          row.image = row.images.length ? row.images[0] : ''
          row.provider = 'ziroom'
          row.source = 'ziroom_shanghai'
          row.tags = ['自如自营']
          items.push(row)
          added++
          if (items.length >= limit) break
        }
        log('[ziroom_ingest] ' + url + ' → +' + added + ' (累计 ' + items.length + ')')
        if (added === 0) break
        if (items.length >= limit) break
      } catch (err) {
        errors.push(url + ': ' + String(err.message || err).slice(0, 120))
        log('[ziroom_ingest] 抓取失败 ' + url + '：' + err.message)
        break
      }
      await sleep(jitter(1500, 3200))
    }
    if (unknownSprite > 0) {
      log('[ziroom_ingest] ⚠ ' + unknownSprite + ' 条价格未能解码（rent_price=0，将滞留人工审核）')
    }
  } finally {
    await browser.close().catch(() => {})
  }
  return { items, errors }
}

function parseArgs(argv) {
  const out = { pages: 3, limit: 100, output: 'items' }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--pages') out.pages = parseInt(argv[++i], 10)
    else if (a === '--limit') out.limit = parseInt(argv[++i], 10)
    else if (a === '--output') out.output = argv[++i]
    else if (a === '--provider') i++ // 忽略（NodeCrawlerRunner 会传，自如脚本单一 provider）
  }
  return out
}

async function main() {
  const opts = parseArgs(process.argv)
  const maxPages = Math.max(1, Math.min(10, opts.pages || 3))
  const limit = Math.max(1, Math.min(300, opts.limit || 100))
  log('[ziroom_ingest] pages≤' + maxPages + ' limit=' + limit)
  const { items, errors } = await crawl(maxPages, limit)
  if (opts.output === 'items') {
    process.stdout.write(JSON.stringify({ ok: items.length > 0, provider: 'ziroom', items: items, errors: errors }) + '\n')
  } else {
    log('[ziroom_ingest] 完成：' + items.length + ' 条，错误 ' + errors.length + ' 处')
  }
}

main().catch((err) => {
  process.stdout.write(JSON.stringify({ ok: false, items: [], error: String(err.message || err) }) + '\n')
  log('[ziroom_ingest] 致命错误：' + (err.stack || err))
  process.exit(1)
})
