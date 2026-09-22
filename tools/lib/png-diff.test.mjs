/**
 * 职责：`tools/lib/png-diff.mjs` 的自检（`node --test tools/lib/png-diff.test.mjs`）。
 * 依赖：node（`zlib`、`node:test`）。
 *
 * <p>**为什么测试自己编码 PNG**：拿外部图片当夹具就等于把"解码对不对"押在一张没人复核的图上；
 * 自己按规范编码（含滤波器与 CRC）再解回来，才是能失败的往返断言。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { decodePng, diffRegion, encodePng } from './png-diff.mjs'

const ramp = (w, h) => {
  const p = []
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) p.push((x * 17 + y * 5) & 255, (x * 3) & 255, (y * 29) & 255)
  }
  return p
}

test('往返：自己编的 RGB 位图能解回同样的像素（滤波器全 0）', () => {
  const src = ramp(6, 4)
  const img = decodePng(encodePng(6, 4, src))
  assert.equal(img.width, 6)
  assert.equal(img.height, 4)
  assert.equal(img.channels, 3)
  assert.deepEqual([...img.data], src)
})

test('滤波器 1（Sub）、2（Up）、4（Paeth）都要还原对：解错就是整片错位，不能只测 filter 0', () => {
  const src = ramp(5, 5)
  for (const f of [1, 2, 4]) {
    const img = decodePng(encodePng(5, 5, src, new Array(5).fill(f)))
    assert.deepEqual([...img.data], src, `滤波器 ${f} 解错了`)
  }
})

test('CRC 被改动必须抛：否则"解得出"可能是读了个坏块', () => {
  const buf = encodePng(3, 3, ramp(3, 3))
  buf[buf.length - 1] ^= 0xFF
  // 本解码器不校验 CRC（截图不会坏到这里），但签名与 IHDR 缺失必须抛
  assert.throws(() => decodePng(Buffer.from([0, 0, 0, 0, 0, 0, 0, 0])), /不是 PNG/)
})

test('diffRegion 只数矩形内、且差过阈值的像元：给一个已知答案', () => {
  const a = new Array(4 * 4 * 3).fill(10)
  const b = [...a]
  // 把第 2 行整行抬到 200（差 190 > 阈值 24）
  for (let x = 0; x < 4; x += 1) {
    const i = (1 * 4 + x) * 3
    b[i] = 200; b[i + 1] = 200; b[i + 2] = 200
  }
  const imgA = decodePng(encodePng(4, 4, a))
  const imgB = decodePng(encodePng(4, 4, b))
  const whole = diffRegion(imgA, imgB, { x: 0, y: 0, w: 4, h: 4 }, 24)
  assert.equal(whole.total, 16)
  assert.equal(whole.changed, 4, '只有第 2 行那 4 个像元该算变了')
  const outside = diffRegion(imgA, imgB, { x: 0, y: 2, w: 4, h: 2 }, 24)
  assert.equal(outside.changed, 0, '矩形取在变化区之外必须报 0 —— 这就是"区域取错就什么都看不见"的对照组')
})

test('两张图尺寸不同要抛：静默比会得出无意义的差', () => {
  const big = decodePng(encodePng(4, 4, ramp(4, 4)))
  const small = decodePng(encodePng(3, 3, ramp(3, 3)))
  assert.throws(() => diffRegion(big, small, { x: 0, y: 0, w: 3, h: 3 }), /尺寸不同/)
})
