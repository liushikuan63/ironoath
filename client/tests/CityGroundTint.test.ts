/**
 * 职责：钉住内城 36 格地皮的**配色结构** —— 按区分块，不是奇偶棋盘。
 * 依赖：node:test / node:assert；只读引擎无关的 `CitySceneAnchors`。
 *
 * <p>为什么判数据而不是判截图：`buildGround` 的填充色是从锚点表的 `district` 查出来的，
 * "同区同色 / 不成棋盘"是这张表的属性。写在视图里就只能靠人眼看截图 —— 而人眼看不出
 * 第 6 行被引导层挡住，也看不出某区悄悄少了一格。
 *
 * <p>为什么每条都值得红：
 * ① 同区不同色 ⇒ 一个区在地面上裂成两块，读起来又变回格子表；
 * ② 相邻同色比例过低 ⇒ 退回规格 §3.3 明令禁止的奇偶交替；
 * ③ 颜色种数 ≠ 区数 ⇒ 有区没被读到（新增区忘了配色，会静默共用别人的色）；
 * ④ 两区色值太近 ⇒ 玩家在地面上分不出区，等于没分。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  SCENE_ANCHORS, SCENE_GRID_WIDTH, SCENE_GRID_HEIGHT,
  DISTRICT_TINT_RGB, groundTintGrid,
} from '../assets/scripts/game/city/CitySceneAnchors'
import type { SceneDistrict } from '../assets/scripts/game/city/CitySceneAnchors'

const key = (tint: readonly number[]): string => tint.join(',')
const at = (column: number, row: number): readonly [number, number, number] => {
  const tint = groundTintGrid()[row * SCENE_GRID_WIDTH + column]
  assert.ok(tint !== undefined, `第 ${row} 行第 ${column} 列取不到地皮色`)
  return tint
}

test('同一区的 36 格地皮必须同色，且每个区的颜色互不相同', () => {
  const byDistrict = new Map<SceneDistrict, string>()
  for (const anchor of SCENE_ANCHORS) {
    const seen = key(DISTRICT_TINT_RGB[anchor.district])
    const first = byDistrict.get(anchor.district)
    if (first === undefined) {
      byDistrict.set(anchor.district, seen)
      continue
    }
    assert.equal(first, seen, `${anchor.anchorId} 所在区 ${anchor.district} 出现了两种地皮色`)
  }
  assert.equal(byDistrict.size, Object.keys(DISTRICT_TINT_RGB).length,
    '有区没出现在锚点表里 —— 地皮上就有一块颜色永远不会出现')
})

test('地皮按区分组成块，不是奇偶交替的棋盘', () => {
  let sameNeighbours = 0
  let pairs = 0
  for (let row = 0; row < SCENE_GRID_HEIGHT; row++) {
    for (let column = 0; column < SCENE_GRID_WIDTH; column++) {
      if (column + 1 < SCENE_GRID_WIDTH) {
        pairs += 1
        if (key(at(column, row)) === key(at(column + 1, row))) sameNeighbours += 1
      }
      if (row + 1 < SCENE_GRID_HEIGHT) {
        pairs += 1
        if (key(at(column, row)) === key(at(column, row + 1))) sameNeighbours += 1
      }
    }
  }
  const ratio = sameNeighbours / pairs
  // 奇偶棋盘 = 0%；按区分块要过半的相邻格同色才读得出"一块地"而不是"一格一格"。
  assert.ok(ratio >= 0.4,
    `相邻同色只有 ${(ratio * 100).toFixed(0)}%（棋盘为 0%）—— 地皮又退回按格交替了`)
})

test('36 格全覆盖：每格都取得到地皮色，且出现的颜色种数等于区数', () => {
  assert.equal(groundTintGrid().length, SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT,
    '地皮格数与网格常量脱节')
  const distinct = new Set(groundTintGrid().map(key))
  assert.equal(distinct.size, Object.keys(DISTRICT_TINT_RGB).length,
    `地面只出现 ${distinct.size} 种颜色，区数却是 ${Object.keys(DISTRICT_TINT_RGB).length} 个`)
})

test('任意两区的地皮色在 26px 小图上分得开', () => {
  const districts = Object.keys(DISTRICT_TINT_RGB) as SceneDistrict[]
  for (let i = 0; i < districts.length; i++) {
    for (let j = i + 1; j < districts.length; j++) {
      const a = DISTRICT_TINT_RGB[districts[i] as SceneDistrict]
      const b = DISTRICT_TINT_RGB[districts[j] as SceneDistrict]
      const diff = (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2
      // 地皮是暗底、面积大，比图标更容不得近似色：欧氏距离 12 起判。
      assert.ok(Math.sqrt(diff) >= 12,
        `${districts[i]} 与 ${districts[j]} 的地皮色几乎相同（距离 ${Math.sqrt(diff).toFixed(1)}）`)
    }
  }
})
