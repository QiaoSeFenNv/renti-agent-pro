#!/usr/bin/env node
/**
 * 豆瓣「上海租房」小组 UGC 采集器（Node + puppeteer-core）。
 *
 * 豆瓣租房小组以个人直租、无中介房源为主，是经典的「无中介」来源。抓取小组讨论列表 → 帖子正文（UGC 文本）
 * → DeepSeek 结构化抽取租金/户型/面积/区域/小区（规则兜底），复用小红书那套抽取管道（同 prompt + 规则）。
 * provider=douban，走候选审核流（UGC 质量参差，全部人工审核，不自动发布）。
 *
 * 输出：stdout 打印 {"ok":true,"provider":"douban","items":[...]} JSON（snake_case）。
 * 用法：node scripts/douban_ingest.cjs --limit 30 --output items
 */
'use strict'

const path = require('path')
const fs = require('fs')

const SH_GROUPS = ['146409', '383972', '190720', '262716', '279962sh'] // 上海租房相关小组（前几个为上海）
const DEFAULT_GROUPS = ['146409', '383972', '190720']
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36'
const SH_DISTRICTS = [
  '黄浦', '徐汇', '长宁', '静安', '普陀', '虹口', '杨浦', '闵行',
  '宝山', '嘉定', '浦东', '金山', '松江', '青浦', '奉贤', '崇明',
]
const LAYOUT_CN = { 一: 1, 二: 2, 两: 2, 三: 3, 四: 4, 五: 5 }

const log = (msg) => process.stderr.write(msg + '\n')
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

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

// --------------------------------------------------------------------------- DeepSeek（复用 xhs 凭据）

function loadDeepseekConfig() {
  const config = {
    key: (process.env.DEEPSEEK_API_KEY || '').trim(),
    base: (process.env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com').trim(),
    model: (process.env.DEEPSEEK_CHAT_MODEL || 'deepseek-chat').trim(),
  }
  if (config.key) return config
  const envLocal = path.resolve(__dirname, '..', 'agent-service', '.env.local')
  if (fs.existsSync(envLocal)) {
    for (const line of fs.readFileSync(envLocal, 'utf8').split(/\r?\n/)) {
      const t = line.trim()
      if (!t || t.startsWith('#') || !t.includes('=')) continue
      const idx = t.indexOf('=')
      const name = t.slice(0, idx)
      const value = t.slice(idx + 1).trim().replace(/^["']|["']$/g, '')
      if (name === 'DEEPSEEK_API_KEY') config.key = value
      else if (name === 'DEEPSEEK_BASE_URL' && value) config.base = value
      else if (name === 'DEEPSEEK_CHAT_MODEL' && value) config.model = value
    }
  }
  return config
}

const LLM_SYSTEM_PROMPT =
  '你是房源信息抽取助手。输入是若干条豆瓣租房帖子（id/标题/正文）。' +
  '请对每条帖子提取字段并输出 JSON 对象：{"items":[{"id":...,"rentPrice":月租金整数,' +
  '"layout":"N室N厅","rentType":"整租|合租","district":"上海行政区名(不带区字,如 浦东/静安)",' +
  '"businessArea":"商圈/板块","community":"小区或公寓名","areaSqm":面积整数}]}。' +
  '无法确定的字段填 null；不要编造；rentPrice 是月租金（元），多个价格取最低的整租/单间价。'

async function extractByLlm(posts, config) {
  if (!config.key || posts.length === 0) return {}
  const lines = posts.map((n) => ({ id: n.external_id, title: n.title.slice(0, 120), content: (n.content || '').slice(0, 400) }))
  const body = {
    model: config.model,
    temperature: 0,
    response_format: { type: 'json_object' },
    messages: [
      { role: 'system', content: LLM_SYSTEM_PROMPT },
      { role: 'user', content: JSON.stringify(lines) },
    ],
  }
  try {
    const res = await fetch(config.base.replace(/\/$/, '') + '/chat/completions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + config.key },
      body: JSON.stringify(body),
    })
    const payload = await res.json()
    const content = payload.choices[0].message.content
    const parsed = JSON.parse(content)
    const items = Array.isArray(parsed) ? parsed : parsed.items
    const result = {}
    for (const it of items || []) {
      if (it && it.id) result[String(it.id)] = it
    }
    log('[llm] DeepSeek 抽取成功：' + Object.keys(result).length + '/' + posts.length + ' 条')
    return result
  } catch (err) {
    log('[llm] DeepSeek 抽取失败，使用规则兜底：' + err.message)
    return {}
  }
}

function extractByRules(title, content) {
  const text = (title || '') + ' ' + (content || '')
  const fields = {}
  for (const re of [
    /(?:月租|租金|价格|房租)[^\d]{0,4}(\d{3,5})/,
    /(\d{3,5})\s*(?:元|块)\s*(?:\/|每)?\s*月/,
    /(\d{3,5})\s*(?:元|块)/,
  ]) {
    const m = text.match(re)
    if (m) {
      const price = parseInt(m[1], 10)
      if (price >= 300 && price <= 50000) {
        fields.rent_price = price
        break
      }
    }
  }
  let m = text.match(/(\d)\s*室\s*(\d)\s*厅/)
  if (m) fields.layout = m[1] + '室' + m[2] + '厅'
  else if ((m = text.match(/([一二两三四五\d])\s*居/))) {
    const c = LAYOUT_CN[m[1]] || (/\d/.test(m[1]) ? parseInt(m[1], 10) : null)
    if (c) fields.layout = c + '室1厅'
  } else if (/单间|主卧|次卧|床位/.test(text)) fields.layout = '1室0厅'
  if (/合租|单间|主卧|次卧|床位|室友/.test(text)) fields.rent_type = '合租'
  else if (/整租|一居|1室|独门独户/.test(text)) fields.rent_type = '整租'
  m = text.match(/(\d{1,3})\s*(?:平米|平方|㎡|平(?!台))/)
  if (m) {
    const area = parseInt(m[1], 10)
    if (area >= 5 && area <= 500) fields.area_sqm = area
  }
  for (const d of SH_DISTRICTS) {
    if (text.includes(d)) {
      fields.district = d
      break
    }
  }
  return fields
}

function buildItem(post, rules, llm) {
  const pick = (llmKey, ruleKey, dflt) => {
    for (const [src, key] of [[llm, llmKey], [rules, ruleKey]]) {
      const v = src[key]
      if (v !== null && v !== undefined && v !== '' && v !== 0) return v
    }
    return dflt
  }
  const item = {
    external_id: post.external_id,
    title: post.title.slice(0, 160),
    source_url: post.url.slice(0, 500),
    city: '上海',
    rent_type: pick('rentType', 'rent_type', '整租'),
    tags: ['豆瓣', '个人直租'],
    provider: 'douban',
    source: 'douban_shanghai',
  }
  for (const [itemKey, llmKey, ruleKey] of [
    ['rent_price', 'rentPrice', 'rent_price'],
    ['layout', 'layout', 'layout'],
    ['district', 'district', 'district'],
    ['business_area', 'businessArea', 'business_area'],
    ['community', 'community', 'community'],
    ['area_sqm', 'areaSqm', 'area_sqm'],
  ]) {
    const v = pick(llmKey, ruleKey)
    if (v !== null && v !== undefined && v !== '' && v !== 0) item[itemKey] = v
  }
  return item
}

// --------------------------------------------------------------------------- 抓取

async function fetchGroupTopics(page, groupId, perGroup) {
  const url = 'https://www.douban.com/group/' + groupId + '/discussion?start=0'
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 40000 })
  await sleep(1200)
  const topics = await page.evaluate(() => {
    const rows = [...document.querySelectorAll('table.olt tr')]
    const out = []
    for (const tr of rows) {
      const a = tr.querySelector('td.title a, .title a')
      if (!a) continue
      const href = a.href || ''
      const m = href.match(/topic\/(\d+)/)
      if (!m) continue
      out.push({ id: m[1], title: (a.title || a.textContent || '').trim(), url: href.split('?')[0] })
    }
    return out
  })
  return topics.slice(0, perGroup)
}

async function fetchTopicBody(page, url) {
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 35000 })
    await sleep(800)
    return await page.evaluate(() => {
      const el = document.querySelector('.topic-content, #link-report, .rich-content, .topic-richtext')
      return el ? el.innerText.replace(/\s+/g, ' ').trim().slice(0, 1500) : ''
    })
  } catch (err) {
    return ''
  }
}

async function crawl(groups, limit, skipDetail) {
  const puppeteer = resolvePuppeteer()
  const chrome = resolveChrome()
  const proxy = process.env.CRAWLER_PROXY || 'http://127.0.0.1:7897'
  const args = ['--no-sandbox', '--disable-gpu', '--lang=zh-CN', '--disable-blink-features=AutomationControlled']
  if (proxy) args.push('--proxy-server=' + proxy)
  log('[douban_ingest] chrome=' + chrome + ' proxy=' + (proxy || '(none)') + ' groups=' + groups.join(','))

  const browser = await puppeteer.launch({ executablePath: chrome, headless: 'new', args, defaultViewport: { width: 1280, height: 900 } })
  const seen = new Set()
  const posts = []
  const errors = []
  try {
    const page = await browser.newPage()
    await page.setUserAgent(UA)
    await page.setExtraHTTPHeaders({ 'Accept-Language': 'zh-CN,zh;q=0.9' })
    const perGroup = Math.max(5, Math.ceil(limit / groups.length))
    for (const g of groups) {
      let topics
      try {
        topics = await fetchGroupTopics(page, g, perGroup)
      } catch (err) {
        errors.push('group ' + g + ': ' + String(err.message).slice(0, 100))
        continue
      }
      log('[douban_ingest] 小组 ' + g + ' → ' + topics.length + ' 帖')
      for (const t of topics) {
        if (seen.has(t.id) || posts.length >= limit) continue
        seen.add(t.id)
        const post = { external_id: 'douban-' + t.id, title: t.title || '豆瓣租房', url: t.url, content: '' }
        if (!skipDetail) {
          post.content = await fetchTopicBody(page, t.url)
          await sleep(700)
        }
        posts.push(post)
      }
      if (posts.length >= limit) break
      await sleep(1500)
    }
  } finally {
    await browser.close().catch(() => {})
  }
  return { posts, errors }
}

function parseArgs(argv) {
  const out = { limit: 30, output: 'items', skipDetail: false, groups: '' }
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--limit') out.limit = parseInt(argv[++i], 10)
    else if (a === '--output') out.output = argv[++i]
    else if (a === '--skip-detail') out.skipDetail = true
    else if (a === '--groups') out.groups = argv[++i]
    else if (a === '--provider' || a === '--pages') i++ // 兼容 NodeCrawlerRunner 传参
  }
  return out
}

async function main() {
  const opts = parseArgs(process.argv)
  const limit = Math.max(1, Math.min(80, opts.limit || 30))
  const groups = opts.groups ? opts.groups.split(/[,，\s]+/).filter(Boolean) : DEFAULT_GROUPS
  log('[douban_ingest] limit=' + limit + ' skipDetail=' + opts.skipDetail)

  const { posts, errors } = await crawl(groups, limit, opts.skipDetail)
  log('[douban_ingest] 抓取 ' + posts.length + ' 帖，开始抽取')

  const config = loadDeepseekConfig()
  const llmResult = await extractByLlm(posts, config)
  const items = []
  for (const post of posts) {
    const rules = extractByRules(post.title, post.content)
    const item = buildItem(post, rules, llmResult[post.external_id] || {})
    // 至少要有租金或小区才入库，纯噪声帖丢弃
    if (item.rent_price || item.community) items.push(item)
  }
  log('[douban_ingest] 组装 ' + items.length + ' 条（含租金 ' + items.filter((i) => i.rent_price).length + ' 条）')

  if (opts.output === 'items') {
    process.stdout.write(JSON.stringify({ ok: items.length > 0, provider: 'douban', items, errors }) + '\n')
  } else {
    log('[douban_ingest] 完成：' + items.length + ' 条')
  }
}

main().catch((err) => {
  process.stdout.write(JSON.stringify({ ok: false, items: [], error: String(err.message || err) }) + '\n')
  log('[douban_ingest] 致命错误：' + (err.stack || err))
  process.exit(1)
})
