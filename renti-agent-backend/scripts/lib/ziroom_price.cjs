'use strict'
/**
 * 自如价格雪碧图解码：自如每次请求轮换雪碧图 PNG（数字顺序打乱），但 10 个数字字形位图在各雪碧图间
 * 像素一致，只是排列不同。故用「参考雪碧图（已知顺序）→ 提取 10 个数字字形 → 模板匹配」在抓取时动态
 * 解出任意雪碧图的顺序，无需 OCR 库或维护映射表。
 *
 * 参考图 scripts/ziroom-price-ref.png 顺序为 6291078453（目视核对得到）。
 */

const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const REF_PATH = path.resolve(__dirname, '..', 'ziroom-price-ref.png')
const REF_ORDER = '6291078453'
const CELLS = 10

/** 解码 8-bit RGBA 非隔行 PNG 为灰度（透明像素记为白）。 */
function decodePngGray(buf) {
  const w = buf.readUInt32BE(16)
  const h = buf.readUInt32BE(20)
  const bitDepth = buf[24]
  const colorType = buf[25]
  if (bitDepth !== 8 || colorType !== 6) {
    throw new Error('unsupported PNG (need 8-bit RGBA), got depth=' + bitDepth + ' color=' + colorType)
  }
  const idat = []
  let off = 8
  while (off < buf.length) {
    const len = buf.readUInt32BE(off)
    const type = buf.toString('ascii', off + 4, off + 8)
    if (type === 'IDAT') idat.push(buf.subarray(off + 8, off + 8 + len))
    off += 12 + len
    if (type === 'IEND') break
  }
  const raw = zlib.inflateSync(Buffer.concat(idat))
  const bpp = 4
  const stride = w * bpp
  const gray = new Uint8Array(w * h)
  const prev = new Uint8Array(stride)
  const cur = new Uint8Array(stride)
  let pos = 0
  for (let y = 0; y < h; y++) {
    const filter = raw[pos++]
    for (let x = 0; x < stride; x++) cur[x] = raw[pos++]
    unfilter(filter, cur, prev, bpp, stride)
    for (let x = 0; x < w; x++) {
      // 字形为不透明橙色像素、背景透明 → 用 alpha 作墨迹信号（不透明=深）。
      const a = cur[x * 4 + 3]
      gray[y * w + x] = 255 - a
    }
    prev.set(cur)
  }
  return { w, h, gray }
}

function unfilter(filter, cur, prev, bpp, stride) {
  for (let x = 0; x < stride; x++) {
    const a = x >= bpp ? cur[x - bpp] : 0
    const b = prev[x]
    const c = x >= bpp ? prev[x - bpp] : 0
    let value = cur[x]
    switch (filter) {
      case 1:
        value = (value + a) & 0xff
        break
      case 2:
        value = (value + b) & 0xff
        break
      case 3:
        value = (value + ((a + b) >> 1)) & 0xff
        break
      case 4:
        value = (value + paeth(a, b, c)) & 0xff
        break
      default:
        break
    }
    cur[x] = value
  }
}

function paeth(a, b, c) {
  const p = a + b - c
  const pa = Math.abs(p - a)
  const pb = Math.abs(p - b)
  const pc = Math.abs(p - c)
  if (pa <= pb && pa <= pc) return a
  if (pb <= pc) return b
  return c
}

/** 提取某单元格(0-9)的字形签名：逐像素二值化后的暗像素向量（列优先），便于 SSD 比较。 */
function cellSignature(img, cellIndex) {
  const cellW = Math.floor(img.w / CELLS)
  const x0 = cellIndex * cellW
  const sig = new Uint8Array(cellW * img.h)
  let k = 0
  for (let x = x0; x < x0 + cellW; x++) {
    for (let y = 0; y < img.h; y++) {
      sig[k++] = img.gray[y * img.w + x] < 140 ? 1 : 0
    }
  }
  return sig
}

function ssd(a, b) {
  let sum = 0
  const n = Math.min(a.length, b.length)
  for (let i = 0; i < n; i++) {
    const d = a[i] - b[i]
    sum += d * d
  }
  return sum
}

let refSignatures = null // digit(0-9) → signature

function loadReference() {
  if (refSignatures) return refSignatures
  const img = decodePngGray(fs.readFileSync(REF_PATH))
  refSignatures = new Array(10)
  for (let i = 0; i < CELLS; i++) {
    const digit = REF_ORDER[i]
    refSignatures[Number(digit)] = cellSignature(img, i)
  }
  return refSignatures
}

/** 对目标雪碧图 PNG 解出单元格索引 → 数字的顺序串（如 "6948527301"）。 */
function decodeSpriteOrder(spriteBuf) {
  const refs = loadReference()
  const img = decodePngGray(spriteBuf)
  let order = ''
  for (let i = 0; i < CELLS; i++) {
    const sig = cellSignature(img, i)
    let best = -1
    let bestScore = Infinity
    for (let d = 0; d < 10; d++) {
      const score = ssd(sig, refs[d])
      if (score < bestScore) {
        bestScore = score
        best = d
      }
    }
    order += String(best)
  }
  return order
}

module.exports = { decodePngGray, decodeSpriteOrder, cellSignature, REF_ORDER }
