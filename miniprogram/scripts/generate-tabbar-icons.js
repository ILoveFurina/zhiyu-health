#!/usr/bin/env node
/**
 * 一次性脚本（备查）：生成 tabBar 图标 home/chat/profile × 普通/选中态，
 * 输出到 miniprogram/assets/tabbar/。仅用 node 内置 zlib 手写 PNG，无第三方依赖。
 *
 * 风格约定：81×81、线性简洁图标（描边 5px），普通态灰绿 #8aa39e，
 * 选中态主色 #00a870（与 app.acss --zy-color-primary 一致）。
 * 4x 超采样后盒式降采样，得到抗锯齿边缘。
 *
 * 用法：node miniprogram/scripts/generate-tabbar-icons.js
 */
const fs = require('fs')
const path = require('path')
const zlib = require('zlib')

const SIZE = 81
const SS = 4 // 超采样倍数
const HALF_STROKE = 2.5 // 81 坐标系下半描边宽

const COLORS = {
  normal: [0x8a, 0xa3, 0x9e],
  active: [0x00, 0xa8, 0x70],
}

/* ---------- 距离场原语（返回像素到描边中心线的距离，≤HALF_STROKE 即着色） ---------- */

function distSeg(px, py, ax, ay, bx, by) {
  const dx = bx - ax
  const dy = by - ay
  const len2 = dx * dx + dy * dy
  let t = len2 === 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
}

const seg = (points) => (px, py) => {
  let d = Infinity
  for (let i = 0; i + 3 < points.length; i += 2) {
    d = Math.min(d, distSeg(px, py, points[i], points[i + 1], points[i + 2], points[i + 3]))
  }
  return d
}

const circleOutline = (cx, cy, r) => (px, py) => Math.abs(Math.hypot(px - cx, py - cy) - r)

function sdRoundBox(px, py, cx, cy, hw, hh, r) {
  const qx = Math.abs(px - cx) - (hw - r)
  const qy = Math.abs(py - cy) - (hh - r)
  return Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0) - r
}

const roundRectOutline = (cx, cy, hw, hh, r) => (px, py) => Math.abs(sdRoundBox(px, py, cx, cy, hw, hh, r))

/** 实心三角：内部返回 0，外部返回到最近边的距离（描边阈值使其尖角略圆，可接受）。 */
function filledTriangle(ax, ay, bx, by, cx, cy) {
  const cross = (x1, y1, x2, y2, x3, y3) => (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1)
  return (px, py) => {
    const d1 = cross(ax, ay, bx, by, px, py)
    const d2 = cross(bx, by, cx, cy, px, py)
    const d3 = cross(cx, cy, ax, ay, px, py)
    const hasNeg = d1 < 0 || d2 < 0 || d3 < 0
    const hasPos = d1 > 0 || d2 > 0 || d3 > 0
    if (!(hasNeg && hasPos)) return 0
    return Math.min(distSeg(px, py, ax, ay, bx, by), distSeg(px, py, bx, by, cx, cy), distSeg(px, py, cx, cy, ax, ay))
  }
}

/** 圆上的一段弧（按最大 y 裁剪，只保留弧顶部分），用于 profile 肩部。 */
const arcTop = (cx, cy, r, maxY) => (px, py) =>
  py > maxY ? Infinity : Math.abs(Math.hypot(px - cx, py - cy) - r)

/** 实心圆角矩形：内部返回 0，外部返回到边界的距离。 */
const filledRoundBox = (cx, cy, hw, hh, r) => (px, py) => Math.max(0, sdRoundBox(px, py, cx, cy, hw, hh, r))

/* ---------- 图标定义（81 坐标系，y 向下） ---------- */

const SHAPES = {
  home: [
    seg([18, 42, 40.5, 20, 63, 42]), // 屋顶
    seg([24, 40, 24, 64, 57, 64, 57, 40]), // 房身 U 形
    filledRoundBox(40.5, 58, 4.5, 6, 1), // 门
  ],
  chat: [
    roundRectOutline(40.5, 36, 26, 18, 10), // 气泡
    filledTriangle(24, 50, 24, 66, 37, 54), // 尾巴
    seg([26, 30, 55, 30]), // 文字行一
    seg([26, 41, 46, 41]), // 文字行二
  ],
  profile: [
    circleOutline(40.5, 30, 11), // 头
    arcTop(40.5, 82, 26, 68), // 肩
  ],
}

/* ---------- PNG 编码（8bit RGBA、无交错、扫描线 filter 0） ---------- */

const CRC_TABLE = (() => {
  const table = new Int32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    table[n] = c
  }
  return table
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length, 0)
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body), 0)
  return Buffer.concat([len, body, crc])
}

function encodePng(rgba, size) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // bit depth
  ihdr[9] = 6 // color type RGBA
  const stride = size * 4 + 1
  const raw = Buffer.alloc(stride * size)
  for (let y = 0; y < size; y++) {
    raw[y * stride] = 0
    rgba.copy(raw, y * stride + 1, y * size * 4, (y + 1) * size * 4)
  }
  return Buffer.concat([
    signature,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ])
}

/* ---------- 渲染：超采样覆盖率 → RGBA ---------- */

function renderIcon(shapeFns, rgb) {
  const out = Buffer.alloc(SIZE * SIZE * 4)
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let covered = 0
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const u = x + (sx + 0.5) / SS
          const v = y + (sy + 0.5) / SS
          let d = Infinity
          for (const fn of shapeFns) d = Math.min(d, fn(u, v))
          if (d <= HALF_STROKE) covered++
        }
      }
      const alpha = Math.round((covered / (SS * SS)) * 255)
      const offset = (y * SIZE + x) * 4
      out[offset] = rgb[0]
      out[offset + 1] = rgb[1]
      out[offset + 2] = rgb[2]
      out[offset + 3] = alpha
    }
  }
  return encodePng(out, SIZE)
}

const outDir = path.join(__dirname, '..', 'assets', 'tabbar')
fs.mkdirSync(outDir, { recursive: true })
for (const [name, shapeFns] of Object.entries(SHAPES)) {
  for (const [state, rgb] of Object.entries(COLORS)) {
    const file = path.join(outDir, state === 'active' ? `${name}-active.png` : `${name}.png`)
    fs.writeFileSync(file, renderIcon(shapeFns, rgb))
    console.log(`written ${path.relative(process.cwd(), file)}`)
  }
}
