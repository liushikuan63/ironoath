/**
 * 职责：`tools/lib/ink-height.mjs` 的自检（`node --test tools/lib/ink-height.test.mjs`）。
 * 依赖：`./png-diff.mjs` 的 `encodePng` / `decodePng`（自己编位图，不拿外部图片当夹具）。
 *
 * <p>这份自检是"尺本身有没有坏"的唯一证据：尺是死的，6 颗疑似被放大的标签就永远只能靠猜。
 * 所以除了正向量高，还配了**空白对照组**（矩形取在墨迹之外必须报 0）与**旋钮自证**
 * （阈值抬到字色差之上必须看不见字）—— 少了这两条，量出什么数都不算数。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { decodePng, encodePng } from './png-diff.mjs'
import { measureInkBand, measureInkDiff, worldBoxToPixelRect, pixelScaleOf, designHeightOf } from './ink-height.mjs'

const BOARD = [38, 28, 22]      // 面板深棕
const INK = [236, 232, 226]     // 字形近白

/** 编一张纯色底图，并在 [y0,y1) 行、[x0,x1) 列画一块"字"。 */
function bitmap(w, h, rects, board = BOARD, ink = INK) {
  const px = []
  for (let i = 0; i < w * h; i += 1) px.push(board[0], board[1], board[2])
  for (const r of rects) {
    const c = r.ink ?? ink
    for (let y = r.y; y < r.y + r.h; y += 1) {
      for (let x = r.x; x < r.x + r.w; x += 1) {
        const i = (y * w + x) * 3
        px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]
      }
    }
  }
  return decodePng(encodePng(w, h, px))
}

test('量出已知墨迹高：10 行字必须正好读回 10', () => {
  const img = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }])
  const m = measureInkBand(img, { x: 2, y: 2, w: 36, h: 56 })
  assert.equal(m.ok, true)
  assert.equal(m.bands.length, 1, '只该有一条带')
  assert.equal(m.inkH, 10)
  assert.equal(m.band.top + m.used.y, 25, '带的绝对行号要对得上画上去的位置')
  assert.deepEqual(m.cols, { x0: 8, x1: 31 }, '横向范围也要对得上，这是"矩形打没打偏"的证据')
})

test('对照组：矩形落在墨迹之外必须报 0，量错地方不能假装量到了', () => {
  const img = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }])
  const miss = measureInkBand(img, { x: 2, y: 36, w: 36, h: 20 })
  assert.equal(miss.inkH, 0)
  assert.equal(miss.bands.length, 0)
})

test('背景自适应：同一块字换两种底板都要量出 10（写死背景常量就会有一边错）', () => {
  const onBrown = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }], [38, 28, 22])
  const onDark = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }], [12, 14, 19])
  assert.equal(measureInkBand(onBrown, { x: 0, y: 15, w: 40, h: 30 }).inkH, 10)
  assert.equal(measureInkBand(onDark, { x: 0, y: 15, w: 40, h: 30 }).inkH, 10)
})

test('阈值旋钮必须生效：抬到字色差之上就看不见字（证明这个数不是白给的）', () => {
  // 弱墨迹：与底板只差 25（三通道平均）
  const img = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }], [38, 28, 22], [63, 53, 47])
  assert.equal(measureInkBand(img, { x: 0, y: 15, w: 40, h: 30 }, { threshold: 20 }).inkH, 10)
  assert.equal(measureInkBand(img, { x: 0, y: 15, w: 40, h: 30 }, { threshold: 60 }).inkH, 0)
})

test('取最高带而不是取上下沿：邻居压进来一两行不能把高度抬高', () => {
  const img = bitmap(40, 60, [
    { x: 8, y: 25, w: 24, h: 10 },          // 目标字
    { x: 2, y: 5, w: 6, h: 2, ink: [200, 190, 180] },  // 上沿邻居
    { x: 30, y: 55, w: 6, h: 3, ink: [200, 190, 180] }, // 下沿邻居
  ])
  const m = measureInkBand(img, { x: 0, y: 0, w: 40, h: 60 })
  assert.equal(m.bands.length, 3, '三条带都要报出来')
  assert.equal(m.inkH, 10, '选中必须是目标那条')
  assert.equal(m.band.top, 25)
})

test('世界盒 → 像素矩形：960×600 设计面对 1440×900 截图，原点在左下要翻到左上', () => {
  const vis = { width: 960, height: 600 }
  const img = { width: 1440, height: 900 }
  assert.deepEqual(pixelScaleOf(img, vis), { sx: 1.5, sy: 1.5 })
  const world = { x: 100, y: 200, width: 50, height: 30 }
  assert.deepEqual(worldBoxToPixelRect(world, vis, img), { x: 150, y: 555, w: 75, h: 45 })
  // 往里缩 4 个设计像素：左右各少 12px、上下各少 6px，且顶边下移
  assert.deepEqual(worldBoxToPixelRect(world, vis, img, 4), { x: 156, y: 561, w: 63, h: 33 })
})

test('折回设计单位：墨迹 15px 在 1.5 缩放下是 10 号字', () => {
  assert.equal(designHeightOf(15, 1.5), 10)
  assert.throws(() => designHeightOf(15, 0), /sy 不合法/)
})

test('没有主导背景时 share 要报出来（中位数分不清谁是背景，这一维不许静默）', () => {
  const thin = bitmap(40, 60, [{ x: 8, y: 25, w: 24, h: 10 }])
  assert.ok(measureInkBand(thin, { x: 0, y: 0, w: 40, h: 60 }).share < 0.2)
  // 这块"字"占区域 56% ⇒ 中位数落到字色上，反过来把那一圈底板当成墨迹：
  // 读数会接近一半，这就是"矩形取太小 / 字真的填满了盒"的警示位，调用方必须看见它
  const huge = bitmap(40, 60, [{ x: 6, y: 6, w: 28, h: 48 }])
  const m = measureInkBand(huge, { x: 0, y: 0, w: 40, h: 60 })
  assert.ok(m.share > 0.4, `share 要能报出"没有主导背景"，实际 ${m.share}`)
})

test('矩形与图不相交要 ok:false 并写明原因，不能给一个 0 当成"没有字"', () => {
  const img = bitmap(20, 20, [])
  const m = measureInkBand(img, { x: 100, y: 100, w: 10, h: 10 })
  assert.equal(m.ok, false)
  assert.match(m.reason, /不相交/)
})

test('vis 不合法要抛：拿 0 去除会把整片的数变成 Infinity 而不报错', () => {
  assert.throws(() => pixelScaleOf({ width: 1440, height: 900 }, { width: 0, height: 600 }), /vis 不合法/)
})

/* ------------------------------------------------------------ 差分法（改前后） */

/** 花底：棋盘格 + 斜向渐变，让"中位数当背景"这条路先失效，才能证明差分法靠的是变化不是颜色。 */
function busy(w, h) {
  const px = []
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      const on = ((x >> 2) + (y >> 2)) % 2 === 0
      px.push(on ? 200 + ((x * 7) & 31) : 20 + ((y * 11) & 60), on ? 40 : 210, on ? 180 : 60)
    }
  }
  return decodePng(encodePng(w, h, px))
}

function overlay(base, rects) {
  const w = base.width
  const h = base.height
  const px = [...base.data]
  for (const r of rects) {
    for (let y = r.y; y < r.y + r.h; y += 1) {
      for (let x = r.x; x < r.x + r.w; x += 1) {
        const i = (y * w + x) * 3
        px[i] = 250; px[i + 1] = 246; px[i + 2] = 240
      }
    }
  }
  return decodePng(encodePng(w, h, px))
}

test('差分法在花底上照样量出 10 行（同一块底，只有那颗字变了）', () => {
  const w = 40
  const h = 60
  const before = busy(w, h)
  const after = overlay(before, [{ x: 8, y: 25, w: 24, h: 10 }])
  const m = measureInkDiff(before, after, { x: 0, y: 0, w, h })
  assert.equal(m.ok, true)
  assert.equal(m.bands.length, 1)
  assert.equal(m.inkH, 10)
  assert.equal(m.band.top, 25)
  assert.deepEqual(m.cols, { x0: 8, x1: 31 })
})

test('差分的对照组：两张一样的图必须报 0（否则"动了旋钮"这件事根本没被钉住）', () => {
  const w = 40
  const h = 60
  const a = busy(w, h)
  const same = decodePng(encodePng(w, h, [...a.data]))
  const m = measureInkDiff(a, same, { x: 0, y: 0, w, h })
  assert.equal(m.inkH, 0)
  assert.equal(m.bands.length, 0)
  assert.equal(m.share, 0)
})

test('差分法要求两张图同尺寸：静默比会得出无意义的数', () => {
  const a = busy(40, 60)
  const b = busy(39, 60)
  assert.throws(() => measureInkDiff(a, b, { x: 0, y: 0, w: 30, h: 30 }), /尺寸不同/)
})

test('单图法在那种花底上会失准（把"为什么需要差分法"钉成断言，不靠嘴说）', () => {
  const w = 40
  const h = 60
  const before = busy(w, h)
  const after = overlay(before, [{ x: 8, y: 25, w: 24, h: 10 }])
  const single = measureInkBand(after, { x: 0, y: 0, w, h })
  // 棋盘格里大量像元与"中位数背景"的差都超过阈值 ⇒ 单图法把整片底都当成墨迹，高度远大于 10
  assert.ok(single.inkH > 20, `花底上单图法应当量歪（这正是需要差分法的理由），实际 inkH=${single.inkH}`)
})
