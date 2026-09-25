/**
 * 职责：钉住"6×6 逻辑格 → 场景锚点"投影表的自洽性。
 * 依赖：node:test / node:assert / node:fs。
 *
 * <p>为什么每条都值得红（都是"只在玩家身上暴露、代码里静默通过"的那类）：
 * ① 少一格 ⇒ 那格上的建筑在城景里**没有落点**，表现是"我的建筑不见了"；
 * ② anchorId 撞号 ⇒ 两格共用一个落点，两栋楼叠在一起；
 * ③ 等距行 ⇒ 规格 §3.3 明令禁止的"把 36 个锚点均匀排成棋盘再称为城景化"；
 * ④ 与 `CityPanel.ts` 的网格常量脱节 ⇒ 投影表按 6×6 写、真实棋盘是别的尺寸，
 *    越界格会静默拿不到锚点。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  SCENE_ANCHORS, SCENE_GRID_WIDTH, SCENE_GRID_HEIGHT, sceneAnchorAt, DISTRICT_NAMES,
  SCENE_VIEW_WIDTH, SCENE_VIEW_HEIGHT,
} from '../assets/scripts/game/city/CitySceneAnchors'

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'hero.json'))) {
      return dir
    }
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/hero.json')
}

test('锚点数与格位覆盖：6×6 一格不多不少，anchorId 唯一', () => {
  assert.equal(SCENE_ANCHORS.length, SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT)
  const cells = new Set(SCENE_ANCHORS.map((a) => `${a.gridX},${a.gridY}`))
  assert.equal(cells.size, SCENE_ANCHORS.length, '有格位重复落点')
  const ids = new Set(SCENE_ANCHORS.map((a) => a.anchorId))
  assert.equal(ids.size, SCENE_ANCHORS.length, 'anchorId 撞号：两格会共用一个落点')
  for (let y = 0; y < SCENE_GRID_HEIGHT; y++) {
    for (let x = 0; x < SCENE_GRID_WIDTH; x++) {
      assert.ok(sceneAnchorAt(x, y) !== null, `格位 (${x},${y}) 没有锚点 —— 城景里这格上的建筑会没有落点`)
    }
  }
})

test('反棋盘：任何一行的水平间距都不许全相等（规格 §3.3 方案 A 的硬约束）', () => {
  for (let y = 0; y < SCENE_GRID_HEIGHT; y++) {
    const xs = SCENE_ANCHORS.filter((a) => a.gridY === y).map((a) => a.x).sort((p, q) => p - q)
    const gaps = xs.slice(1).map((x, i) => Math.round(x - (xs[i] ?? x)))
    // 本仓库开了 noUncheckedIndexedAccess，取首项要兜底 —— 这里只为拼错误文案
    assert.notEqual(new Set(gaps).size, 1,
      `第 ${y} 行锚点间距全相等（${gaps[0] ?? '?'}）—— 均匀排成棋盘不叫城景化`)
  }
})

test('锚点几何合法：脚印为正、落在工作视图内、越界格返回 null', () => {
  for (const a of SCENE_ANCHORS) {
    assert.ok(a.footprintWidth > 0 && a.footprintDepth > 0, `${a.anchorId} 脚印非正`)
    // 用工作视图常量而不是写死数字：视图尺寸一改（563 → 625 让比例与屏幕同源），
    // 写死的那份就在这里变成假绿或假红
    assert.ok(a.x > 0 && a.x < SCENE_VIEW_WIDTH && a.y > 0 && a.y < SCENE_VIEW_HEIGHT,
      `${a.anchorId} 落点越出工作视图`)
    assert.ok(Object.keys(DISTRICT_NAMES).includes(a.district), `${a.anchorId} 区名未登记`)
  }
  assert.equal(sceneAnchorAt(-1, 0), null)
  assert.equal(sceneAnchorAt(SCENE_GRID_WIDTH, 0), null)
  assert.equal(sceneAnchorAt(0.5, 0), null, '非整数格位不该拿到锚点')
})

test('投影表与 CityPanel 的网格常量同源（脱节必须在这里红，而不是在运行时少格）', () => {
  const src = fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'game', 'city', 'CityPanel.ts'), 'utf8')
  const w = /CITY_GRID_WIDTH\s*=\s*(\d+)/.exec(src)
  const h = /CITY_GRID_HEIGHT\s*=\s*(\d+)/.exec(src)
  assert.ok(w !== null && h !== null, '读不到 CityPanel 的网格常量：本用例的取法要跟着改')
  assert.equal(Number(w[1]), SCENE_GRID_WIDTH, 'CityPanel 改了宽，投影表没跟上')
  assert.equal(Number(h[1]), SCENE_GRID_HEIGHT, 'CityPanel 改了高，投影表没跟上')
})
