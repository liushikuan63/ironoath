/**
 * 职责：不依赖任何第三方库地**解码 PNG 并按区域比像素差**，给横扫量具做"字被图形底板盖住"这一维的地基。
 * 依赖：node（`zlib`）。
 *
 * <p><b>为什么不用 `gl.readPixels`</b>：Cocos 建 context 时没开 `preserveDrawingBuffer`，
 * 帧一呈现 drawingBuffer 就失效 —— 实测在页面里读回来是**全零且 `getError()` 返回 0**（不是报错，是空），
 * 拿它比差异会得到"永远没有差异"的假绿。而浏览器截图走合成器，**能拍到 WebGL 内容**
 * （横扫那 20 多张 `label-fit-verify/*.png` 就是证据），所以这一维只能走"截图 → 解 PNG → 比像素"。
 *
 * <p><b>为什么手写解码</b>：本仓库的探针跑在 CI 与本机两端，装图像库要动锁文件；
 * PNG 的解码其实就是 `inflate` + 五种滤波器还原，够小可验证（同目录 `png-diff.test.mjs` 用**自己编码**的
 * 位图做往返断言，不依赖任何外部图片）。
 */
import { inflateSync } from 'node:zlib'

const SIG = [137, 80, 78, 71, 13, 10, 26, 10]

/** 解一张 PNG（8 位深、colorType 0/2/4/6、非隔行），返回 { width, height, channels, data:Uint8Array }。 */
export function decodePng(buf) {
  for (let i = 0; i < 8; i += 1) {
    if (buf[i] !== SIG[i]) throw new Error('不是 PNG（签名不匹配）')
  }
  let pos = 8
  let ihdr = null
  const idat = []
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos)
    const type = buf.toString('ascii', pos + 4, pos + 8)
    const body = buf.subarray(pos + 8, pos + 8 + len)
    if (type === 'IHDR') {
      ihdr = {
        width: body.readUInt32BE(0),
        height: body.readUInt32BE(4),
        depth: body[8],
        colorType: body[9],
        interlace: body[12],
      }
    } else if (type === 'IDAT') {
      idat.push(body)
    } else if (type === 'IEND') {
      break
    }
    pos += 12 + len
  }
  if (ihdr === null) throw new Error('PNG 没有 IHDR')
  if (ihdr.depth !== 8) throw new Error(`只支持 8 位深，拿到 ${ihdr.depth}`)
  if (ihdr.interlace !== 0) throw new Error('不支持隔行 PNG')
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ihdr.colorType]
  if (channels === undefined) throw new Error(`不支持的 colorType ${ihdr.colorType}`)
  const raw = inflateSync(Buffer.concat(idat))
  const { width, height } = ihdr
  const stride = width * channels
  const out = new Uint8Array(stride * height)
  let rp = 0
  let prev = new Uint8Array(stride)
  for (let y = 0; y < height; y += 1) {
    const filter = raw[rp]
    rp += 1
    const line = raw.subarray(rp, rp + stride)
    rp += stride
    const cur = out.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < stride; x += 1) {
      const a = x >= channels ? cur[x - channels] : 0
      const b = prev[x]
      const c = x >= channels ? prev[x - channels] : 0
      let v = line[x]
      if (filter === 1) v = v + a
      else if (filter === 2) v = v + b
      else if (filter === 3) v = v + Math.floor((a + b) / 2)
      else if (filter === 4) {
        const p = a + b - c
        const pa = Math.abs(p - a)
        const pb = Math.abs(p - b)
        const pc = Math.abs(p - c)
        v = v + (pa <= pb && pa <= pc ? a : (pb <= pc ? b : c))
      } else if (filter !== 0) {
        throw new Error(`未知滤波器 ${filter}`)
      }
      cur[x] = v & 255
    }
    prev = cur
  }
  return { width, height, channels, data: out }
}

/** 把 RGBA/RGB/灰度像元统一成 [r,g,b]（比差异时不关心 alpha，底板是画上去的不透明色）。 */
function rgbOf(img, x, y) {
  const ch = img.channels
  const i = (y * img.width + x) * ch
  if (ch === 1 || ch === 2) return [img.data[i], img.data[i], img.data[i]]
  return [img.data[i], img.data[i + 1], img.data[i + 2]]
}

/**
 * 比两张同尺寸图的**给定矩形**（左上角 + 宽高，像素单位）：
 * `changed` = 至少差 `threshold` 的像元数，`meanDelta` = 所有像元三通道差均值的平均。
 * 只报数不判红 —— 阈值由调用方（量具）拿"已知真缺陷"标定，别在这里写死。
 */
export function diffRegion(before, after, rect, threshold = 24) {
  const { x, y, w, h } = rect
  if (before.width !== after.width || before.height !== after.height) {
    throw new Error(`两张图尺寸不同：${before.width}x${before.height} vs ${after.width}x${after.height}`)
  }
  const x1 = Math.min(before.width, x + w)
  const y1 = Math.min(before.height, y + h)
  let changed = 0
  let total = 0
  let sumDelta = 0
  for (let py = y; py < y1; py += 1) {
    for (let px = x; px < x1; px += 1) {
      const a = rgbOf(before, px, py)
      const b = rgbOf(after, px, py)
      const d = (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2])) / 3
      total += 1
      sumDelta += d
      if (d > threshold) changed += 1
    }
  }
  return { total, changed, ratio: total === 0 ? 0 : changed / total, meanDelta: total === 0 ? 0 : sumDelta / total }
}
