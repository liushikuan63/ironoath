/**
 * 职责：技能升级选书的纯逻辑用例（V03-d 最后一条）。
 * 依赖：node:test + game/hero/SkillPick（不碰 cc）。
 *
 * <p><b>每条钉一个会做错的地方</b>：① 候选按 `effectKind` 筛；② **槽位来自 `effectTarget`**
 * —— 两本书在 type 与 effectKind 下完全同型，猜一个槽位再赌一本对得上的书就是这条线的错法；
 * ③ 满级判定用 `HeroView` 下发的两个数（不是客户端另立一个上限）；
 * ④ 没标注 / 已满级的行点了不算选中；⑤ 选中项是 `skillSlot` 的唯一来源。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { SKILL_UP_KIND, buildSkillPick, skillRows } from '../assets/scripts/game/hero/SkillPick'
import type { BagItem } from '../assets/scripts/net/generated/BagProtocol'

function book(overrides: Partial<BagItem> = {}): BagItem {
  return {
    itemId: 'item_hero_skillbook_main', name: '主技能秘卷', type: 'MATERIAL', rarity: 'SR',
    obtainFrom: '章节宝箱', count: 3, stackMax: 999, sortKey: 100,
    effectKind: SKILL_UP_KIND, effectTarget: 'MAIN', ...overrides,
  }
}

const subBook = book({
  itemId: 'item_hero_skillbook_sub', name: '副技能残卷', rarity: 'R', effectTarget: 'SUB',
})

/** 一件同 type、同 effectKind 之外的材料（觉醒石）—— 只按 type 筛就会误放进来。 */
const stone = book({ itemId: 'item_hero_awaken_1', name: '觉醒石·初阶', effectKind: 'AWAKEN_HERO' })

const stage = { mainLevel: 3, subLevel: 1, maxLevel: 10 }

test('候选只按 effectKind 筛：同为 MATERIAL 的觉醒石不进技能弹层', () => {
  const rows = skillRows([book(), subBook, stone], stage)
  assert.deepEqual(rows.map((r) => r.itemId),
    ['item_hero_skillbook_main', 'item_hero_skillbook_sub'])
})

test('槽位由 effectTarget 决定：两本书同 type 同 effectKind，只有这一列分得开', () => {
  const rows = skillRows([book(), subBook], stage)
  assert.deepEqual(rows.map((r) => [r.slot, r.slotText]),
    [['MAIN', '主技能'], ['SUB', '副技能']])
  assert.equal(rows[0]?.levelText, 'Lv3 → Lv4（上限 10）', '主技能读 mainLevel')
  assert.equal(rows[1]?.levelText, 'Lv1 → Lv2（上限 10）', '副技能读 subLevel —— 两路各自独立')
})

test('没标注 effectTarget 的书不猜槽位：灰掉并说明是配置的事', () => {
  const rows = skillRows([book({ effectTarget: null })], stage)
  assert.equal(rows[0]?.slot, null)
  assert.equal(rows[0]?.usable, false)
  assert.equal(rows[0]?.reason, '这本没标注主 / 副技能')
  const view = buildSkillPick([book({ effectTarget: null })], stage,
    'item_hero_skillbook_main')
  assert.equal(view.selectedItemId, null, '没标注的书点了也不该成为选中项')
  assert.equal(view.canSend, false)
})

test('满级判定用 HeroView 那两个数：主技能满了不影响副技能那本可用', () => {
  const rows = skillRows([book(), subBook], { mainLevel: 10, subLevel: 1, maxLevel: 10 })
  assert.equal(rows[0]?.usable, false)
  assert.equal(rows[0]?.reason, '主技能已满级')
  assert.equal(rows[0]?.levelText, 'Lv10（上限 10，已满级）', '满级还写"Lv10 → Lv11"就是把玩家往拒绝上推')
  assert.equal(rows[1]?.usable, true)
})

test('两路都满级：确认键说实话，而不是继续写着"先选技能书"', () => {
  const view = buildSkillPick([book(), subBook], { mainLevel: 10, subLevel: 10, maxLevel: 10 })
  assert.equal(view.canSend, false)
  assert.equal(view.sendText, '没有可用技能书')
})

test('选中项是 skillSlot 的唯一来源；灰行点了不算选中', () => {
  const items = [book(), subBook]
  const view = buildSkillPick(items, stage)
  assert.equal(view.canSend, false, '默认没选，不给发')
  assert.equal(view.sendText, '先选技能书')
  const pickedSub = buildSkillPick(items, stage, 'item_hero_skillbook_sub')
  assert.equal(pickedSub.selectedSlot, 'SUB')
  assert.equal(pickedSub.sendText, '确认升级')
  const maxed = buildSkillPick(items, { mainLevel: 3, subLevel: 10, maxLevel: 10 },
    'item_hero_skillbook_sub')
  assert.equal(maxed.selectedItemId, null, '副技能已满级，那本不该被选中')
  assert.equal(maxed.selectedSlot, null)
})

test('一本技能书都没有时给说明行，且不发', () => {
  const view = buildSkillPick([stone], stage)
  assert.equal(view.rows.length, 0)
  assert.equal(view.emptyText, '背包里没有技能书')
  assert.equal(view.canSend, false)
})
