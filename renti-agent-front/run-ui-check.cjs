/* eslint-disable no-console */
// 全站 UI 走查脚本：注册测试用户 → 逐页截图 → 汇总 console 错误（跑完可删）
const fs = require('fs')
const path = require('path')
const puppeteer = require('puppeteer-core')

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = path.join(__dirname, 'shots')

const EMAIL = `ui-check-${Date.now()}@rentai.local`
const PASS = 'UiCheck12345'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function prepareUser() {
  const reg = await fetch(`${API}/api/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PASS, nickname: 'UI巡检' }),
  }).then((r) => r.json())
  const code = reg?.devVerificationCode || reg?.devCode
  if (!code) throw new Error('register: no devVerificationCode: ' + JSON.stringify(reg).slice(0, 300))
  const ver = await fetch(`${API}/api/auth/verify-email`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, code }),
  }).then((r) => r.json())
  if (ver && ver.ok === false) throw new Error('verify failed: ' + JSON.stringify(ver).slice(0, 300))
  console.log('user ready:', EMAIL)
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true })
  await prepareUser()

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--window-size=1440,900', '--disable-gpu', '--no-first-run', '--lang=zh-CN'],
    defaultViewport: { width: 1440, height: 900 },
  })
  const page = await browser.newPage()

  const errors = {}
  let current = 'init'
  page.on('console', (msg) => {
    if (msg.type() === 'error') (errors[current] ||= []).push(msg.text().slice(0, 220))
  })
  page.on('pageerror', (err) => (errors[current] ||= []).push('PAGEERROR ' + String(err).slice(0, 220)))

  const shot = async (name, { url, wait = 1200, fullPage = false, before } = {}) => {
    current = name
    if (url) await page.goto(BASE + url, { waitUntil: 'networkidle2', timeout: 45000 }).catch((e) => {
      ;(errors[name] ||= []).push('GOTO ' + String(e).slice(0, 120))
    })
    if (before) await before()
    await sleep(wait)
    await page.screenshot({ path: path.join(OUT, name + '.png'), fullPage })
    console.log('shot:', name)
  }

  /* ---- 公开页 ---- */
  await shot('01-home-hero', { url: '/', wait: 3200 })
  await shot('02-home-full', { wait: 400, fullPage: true })
  await shot('03-cities', { url: '/cities', wait: 1600 })
  await shot('04-login', { url: '/login', wait: 900 })
  await shot('05-register', { url: '/register', wait: 900 })

  /* ---- 用户登录（真实 UI 流程）---- */
  current = 'login-flow'
  await page.goto(BASE + '/login', { waitUntil: 'networkidle2' })
  await page.type('input[name="email"]', EMAIL, { delay: 10 })
  await page.type('input[name="password"]', PASS, { delay: 10 })
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }).catch(() => {}),
    page.click('button[type="submit"]'),
  ])
  console.log('after login url:', page.url())

  /* ---- 地图工作台 ---- */
  await shot('06-map-idle', { url: '/city/' + encodeURIComponent('上海'), wait: 6500 })
  current = '07-map-search'
  const box = await page.$('#workspace-query')
  if (box) {
    await box.type('人民广场附近预算6500的一居室', { delay: 15 })
    await page.keyboard.press('Enter')
    await sleep(15000)
  }
  await shot('07-map-search', { wait: 500 })

  /* ---- 用户中心 / 房源详情 ---- */
  await shot('08-workspace', { url: '/workspace', wait: 1800 })
  current = 'property-probe'
  // 回到工作台从搜索结果卡片拿真实房源 id 访问详情页
  await page.goto(BASE + '/city/' + encodeURIComponent('上海') + '?q=' + encodeURIComponent('人民广场附近预算6500的一居室'), { waitUntil: 'networkidle2', timeout: 45000 }).catch(() => {})
  await page.waitForSelector('[id^="listing-card-"]', { timeout: 20000 }).catch(() => {})
  let listingId = null
  try {
    listingId = await page.$eval('[id^="listing-card-"]', (el) => el.id.replace('listing-card-', ''))
  } catch {}
  if (listingId) {
    await shot('09-property', { url: '/property/' + encodeURIComponent(listingId), wait: 4000, fullPage: true })
  } else {
    console.log('property: no listing card found, skipped')
  }

  /* ---- 管理端 ---- */
  await shot('10-admin-login', { url: '/admin/login', wait: 900 })
  current = 'admin-login-flow'
  const inputs = await page.$$('input')
  if (inputs.length >= 2) {
    await inputs[0].type('admin', { delay: 10 })
    await inputs[1].type('admin123', { delay: 10 })
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle2', timeout: 20000 }).catch(() => {}),
      page.click('button[type="submit"]'),
    ])
  }
  console.log('after admin login url:', page.url())
  await shot('11-admin-overview', { url: '/admin', wait: 2200 })
  await shot('12-admin-listings', { url: '/admin/listings', wait: 2200 })
  await shot('13-admin-integrations', { url: '/admin/integrations', wait: 2200 })
  await shot('14-admin-ingestion', { url: '/admin/ingestion', wait: 2200 })

  await browser.close()
  fs.writeFileSync(path.join(OUT, 'console-errors.json'), JSON.stringify(errors, null, 2))
  console.log('console errors:', JSON.stringify(errors, null, 1).slice(0, 2400))
}

main().catch((err) => {
  console.error('FATAL', err)
  process.exit(1)
})
