/**
 * 职责：升级喂道具的纯逻辑用例（V03-d，口径＝逐件选数量）。
 * 依赖：node:test + game/hero/ExpPick（不碰 cc）。
 *
 * <p><b>四处最容易做假的地方</b>：① 候选只能按 `effectKind` 筛（`type` 分不出经验书与别的材料）；
 * ② 默认全 0，不替玩家预选；③ 计数夹在 [0, 持有数]；④ 全 0 不发（别发一个注定被拒的请求）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildExpPick, bumpPick, expPickRows, pickedPayload, HERO_EXP_KIND,
} from '../assets/scripts/game/hero/ExpPick'
import type { BagItem } from '../assets/scripts/net/generated/BagProtocol'

function item(overrides: Partial<BagItem> = {}): BagItem {
  return {
    itemId: 'item_hero_exp_s', name: '小经验书', type: 'MATERIAL', rarity: 'R',
    obtainFrom: '主线任务', count: 5, stackMax: 99, sortKey: 100,
    effectKind: HERO_EXP_KIND, ...overrides,
  }
}

/** 一件同 type 的普通材料（技能书）——它就是"只按 type 筛"会误放进来的那种行。 */
const skillBook = item({
  itemId: 'item_hero_skillbook_main', name: '技能书', effectKind: 'UP_HERO_SKILL', count: 3,
})

test('候选只按 effectKind 筛：同 type 的技能书不进升级弹层', () => {
  const rows = expPickRows([item(), skillBook, item({ itemId: 'item_hero_exp_m', name: '中经验书' })])
  assert.deepEqual(rows.map((r) => r.itemId), ['item_hero_exp_s', 'item_hero_exp_m'])
  assert.equal(rows.some((r) => r.itemId === 'item_hero_skillbook_main'), false,
    'type 都是 MATERIAL，只有 effectKind 分得开')
})

test('默认全 0：裁决是"逐件选数量"，预选就是把决策权拿走', () => {
  const view = buildExpPick([item()])
  assert.equal(view.rows[0]?.picked, 0)
  assert.equal(view.totalPicked, 0)
  assert.equal(view.canSend, false, '全 0 不发：服务端要求 expItems 非空')
})

test('+ 到持有数就停，- 到 0 就停（按到边界再按一下是正常操作，不该报错）', () => {
  let picks = bumpPick(expPickRows([item({ count: 2 })]), 'item_hero_exp_s', 1)
  picks = bumpPick(expPickRows([item({ count: 2 })], picks), 'item_hero_exp_s', 1)
  picks = bumpPick(expPickRows([item({ count: 2 })], picks), 'item_hero_exp_s', 1)
  assert.equal(picks.item_hero_exp_s, 2, '越上界夹在持有数')
  picks = bumpPick(expPickRows([item({ count: 2 })], picks), 'item_hero_exp_s', -5)
  assert.equal(picks.item_hero_exp_s, 0, '越下界夹在 0')
})

test('多件各自计数：加一件不影响另一件', () => {
  const rows = expPickRows([item(), item({ itemId: 'item_hero_exp_m', count: 4 })])
  const picks = bumpPick(rows, 'item_hero_exp_m', 3)
  assert.equal(picks.item_hero_exp_s, 0)
  assert.equal(picks.item_hero_exp_m, 3)
  assert.equal(buildExpPick([item(), item({ itemId: 'item_hero_exp_m', count: 4 })], picks).totalPicked, 3)
})

test('payload 只带选了的项（0 的不上报：服务端要的是"喂什么"，不是"不喂什么"）', () => {
  const items = [item(), item({ itemId: 'item_hero_exp_m', count: 4 })]
  const picks = bumpPick(expPickRows(items), 'item_hero_exp_m', 2)
  assert.deepEqual(pickedPayload(expPickRows(items, picks)), [{ itemId: 'item_hero_exp_m', count: 2 }])
})

test('一件经验道具都没有时给说明行，且不发', () => {
  const view = buildExpPick([skillBook])
  assert.equal(view.rows.length, 0)
  assert.equal(view.emptyText, '还没有经验道具')
  assert.equal(view.canSend, false)
})

test('夹取不吃非法值：NaN/小数/负数都落到合法范围（视图一步只会越界一格，但不该有崩溃路径）', () => {
  const rows = expPickRows([item({ count: 3 })], { item_hero_exp_s: Number.NaN })
  assert.equal(rows[0]?.picked, 0)
  const frac = expPickRows([item({ count: 3 })], { item_hero_exp_s: 2.7 })
  assert.equal(frac[0]?.picked, 2, '取整而不是四舍五入（多喂一本是不可逆的）')
  const over = expPickRows([item({ count: 3 })], { item_hero_exp_s: 99 })
  assert.equal(over[0]?.picked, 3)
})
