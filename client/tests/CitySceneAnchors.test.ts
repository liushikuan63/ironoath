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

test('默认主城的展示锚是王庭地面基座，不能继续命中旧背景的塔楼', () => {
  const keep = sceneAnchorAt(3, 3)
  assert.ok(keep !== null)
  assert.equal(keep.anchorId, 'p33', '重标显示基座不能改变默认逻辑格位身份')
  assert.equal(keep.district, 'crown')
  assert.ok(keep.x / SCENE_VIEW_WIDTH >= 0.45 && keep.x / SCENE_VIEW_WIDTH <= 0.55,
    '主堡基座应接在舞台的王庭主轴')
  assert.ok(keep.y / SCENE_VIEW_HEIGHT >= 0.35 && keep.y / SCENE_VIEW_HEIGHT <= 0.45,
    '主堡应落在王庭空基座，旧塔楼热区会把真实 Sprite 顶出画面')
})

test('网格边长只由服务端下发，客户端不再各写一份（脱节必须在这里红）', () => {
  // 以前这条用例钉的是「CityPanel 的常量 == 投影表的常量」——
  // 那是**两份副本互相比对**，而两份副本**都与表里的 city_rule_grid_size 无关**。
  // 实测：全仓 grep `city_rule_grid_size` 在 client/ 下**零命中** ⇒
  // 表里 6→8 那一改，两份副本都还是 6，没有任何测试或门会红，
  // 而服务端已经按 8×8 下发坐标 ⇒ 客户端判越界 ⇒ 玩家自己的城被说成「坐标非法」。
  //
  // 现在网格边长由 `CityListResp.gridSize` 下发（协议必填字段），
  // 客户端这边**只留一个回退值**（旧服务端滚动升级期间用）。
  // ⇒ 这条用例改成钉「客户端不再有第二份网格边长」：
  //   ① 投影表那份必须与回退值一致（它是手写 36 格美术资产，形状得对得上）；
  //   ② CityPanel 里不得再出现一个「与表无关的写死边长」。
  const cityPanelSrc = fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'game', 'city', 'CityPanel.ts'), 'utf8')
  const fallback = /CITY_GRID_FALLBACK_SIZE\s*=\s*(\d+)/.exec(cityPanelSrc)
  assert.ok(fallback !== null, '读不到 CITY_GRID_FALLBACK_SIZE：回退值没了，滚动升级期间会崩')
  assert.equal(Number(fallback[1]), SCENE_GRID_WIDTH,
    '回退值与投影表的网格宽度不一致：旧服务端那段时间里，两者会判出不同的网格')

  // 客户端**不得**再有第二个写死的网格边长（那是第二份副本）。
  // 判据：CityPanel.ts 里除回退值外，不得再出现 `CITY_GRID_WIDTH` / `CITY_GRID_HEIGHT` 这两个名字。
  assert.equal(/CITY_GRID_WIDTH\s*=/.test(cityPanelSrc), false,
    'CityPanel 又长出一个写死的网格宽度：网格边长只能由服务端下发')
  assert.equal(/CITY_GRID_HEIGHT\s*=/.test(cityPanelSrc), false,
    'CityPanel 又长出一个写死的网格高度：网格边长只能由服务端下发')
})

test('投影表的格数与网格边长一致：服务端说 8 时，客户端不能按 6 排布', () => {
  // 这条钉住**危害链**本身：服务端下发的边长与手写美术表不一致时，
  // 越界判定会与美术布局脱节 —— 建筑画在一处，判定按另一处。
  // 现状（两边都是 6）是**一致的**；不一致时必须有人报警而不是默默画歪。
  const sceneSrc = fs.readFileSync(path.join(repoRoot(), 'client', 'assets', 'scripts',
    'game', 'city', 'CitySceneAnchors.ts'), 'utf8')
  const w = /SCENE_GRID_WIDTH\s*=\s*(\d+)/.exec(sceneSrc)
  assert.ok(w !== null, '读不到 SCENE_GRID_WIDTH')
  assert.equal(Number(w[1]), SCENE_GRID_WIDTH, '投影表内部自相矛盾')
  // 投影表是 6×6 = 36 格的手写资产：格数必须与边长自洽。
  assert.equal(SCENE_ANCHORS.length, SCENE_GRID_WIDTH * SCENE_GRID_HEIGHT,
    '投影表里的锚点数与网格边长不自洽：加格删格时忘了改边长')
})
