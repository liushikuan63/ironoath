/**
 * 职责：钉住"世界地图在默认缩放档必须铺满视口"这条换算。
 * 依赖：node:test / node:assert；只读引擎无关的 `game/world/WorldZoom`。
 *
 * <p>为什么值得立一条门：原来档位 0 是写死的 `8`，注释里那句
 * "3 块 × 32 格 × 8px = 768px ≈ 设计分辨率宽度"在**它自己那台设计宽度下成立**，
 * 换到 1440×900 就只剩约 53% 覆盖率 —— 编译过、类型对、没有任何测试会红，
 * 症状是玩家打开地图看到半屏黑。这类"常量对某一个尺寸凑巧对"的缺陷只能靠算出来判。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  CHUNKS_PER_SIDE_ON_SCREEN, CELL_ZOOM_SCALES, baseCellPixels, cellPixels,
} from '../assets/scripts/game/world/WorldZoom'

/** 真机会出现的画布：横屏 PC / 横屏手机 / 竖屏 / 小屏。 */
const VIEWPORTS: readonly (readonly [number, number])[] = [
  [1440, 900], [1280, 720], [1024, 768], [750, 1334], [375, 667], [320, 480],
]

test('默认缩放档：3×3 块铺满视口的长边，不留黑边', () => {
  for (const [w, h] of VIEWPORTS) {
    const cell = cellPixels(0, baseCellPixels(w, h, 32))
    const covered = cell * CHUNKS_PER_SIDE_ON_SCREEN * 32
    assert.ok(covered >= Math.max(w, h) - 0.5,
      `${w}×${h} 的画布上地图只有 ${covered.toFixed(0)}px 宽，会留黑边`)
  }
})

test('写死 8px 的旧口径在宽画布上必然铺不满（这条就是当初那个缺陷）', () => {
  // 判据自己也要能失败：如果哪天有人把换算改回常量，这条会红。
  const legacy = 8 * CHUNKS_PER_SIDE_ON_SCREEN * 32
  assert.ok(legacy < 1440, '旧口径竟然已经能铺满 1440 —— 本条判据失效，删掉它')
  assert.ok(cellPixels(0, baseCellPixels(1440, 900, 32)) * CHUNKS_PER_SIDE_ON_SCREEN * 32 >= legacy,
    '新换算的覆盖范围不该比旧的还小')
})

test('档位倍率单调放大，且越界档位被钳到最大档而不是崩成 0', () => {
  const base = baseCellPixels(1440, 900, 32)
  assert.ok(cellPixels(1, base) > cellPixels(0, base), '放大档反而变小，缩放方向反了')
  assert.equal(cellPixels(1, base), base * (CELL_ZOOM_SCALES[1] ?? 3))
  assert.equal(cellPixels(9, base), cellPixels(CELL_ZOOM_SCALES.length - 1, base))
  assert.equal(cellPixels(-3, base), cellPixels(0, base))
})

test('chunkSize 为 0 时不得算出 Infinity：那会画出一张空白地图而不是报错', () => {
  assert.ok(Number.isFinite(baseCellPixels(1440, 900, 0)))
  assert.ok(Number.isFinite(cellPixels(0, baseCellPixels(1440, 900, 0))))
  assert.ok(Number.isFinite(cellPixels(0, baseCellPixels(0, 0, 32))))
})

test('每一档都给出正数边长（除零与空画面是同一件事的两面）', () => {
  const base = baseCellPixels(1280, 720, 32)
  for (let zoom = 0; zoom < CELL_ZOOM_SCALES.length; zoom += 1) {
    assert.ok(cellPixels(zoom, base) > 0, `档位 ${zoom} 的边长不是正数`)
  }
})
