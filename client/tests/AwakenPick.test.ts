/**
 * 职责：觉醒选石头的纯逻辑用例（V03-d 第二条养成线）。
 * 依赖：node:test + node:fs + game/hero/AwakenPick（不碰 cc）。
 *
 * <p><b>每条都钉一个会做错的地方</b>：
 * ① 候选只能按 `effectKind` 筛（觉醒石与经验书同为 MATERIAL）；
 * ② 两块石按当前阶分流 —— 最后一阶只认高阶石、其余阶只认初阶石（记反了整个觉醒线点不动）；
 * ③ 已达上限时两块都灰、且 `stageText` 说实话（不能显示"第 3 阶 → 第 4 阶"）；
 * ④ 灰掉的行即使被点到也不算选中（发出去只是白拿一条拒绝）；
 * ⑤ 客户端抄的这条规则与服务端源码仍然同一句 —— 这一条是镜像的保险，见文件末。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {
  AWAKEN_KIND, FINAL_TIER_STONE_RARITY, awakenRows, buildAwakenPick,
} from '../assets/scripts/game/hero/AwakenPick'
import type { BagItem } from '../assets/scripts/net/generated/BagProtocol'

function stone(overrides: Partial<BagItem> = {}): BagItem {
  return {
    itemId: 'item_hero_awaken_1', name: '觉醒石·初阶', type: 'MATERIAL', rarity: 'SR',
    obtainFrom: '赛季通行证', count: 4, stackMax: 999, sortKey: 100,
    effectKind: AWAKEN_KIND,
    effectTarget: null, ...overrides,
  }
}

const highStone = stone({
  itemId: 'item_hero_awaken_2', name: '觉醒石·高阶', rarity: 'SSR', count: 1,
})

/** 一块同 type 的经验书 —— 它就是"只按 type 筛"会误放进来的那种行。 */
const expBook = stone({ itemId: 'item_hero_exp_s', name: '小经验书', effectKind: 'GRANT_HERO_EXP' })

test('候选只按 effectKind 筛：同为 MATERIAL 的经验书不进觉醒弹层', () => {
  const rows = awakenRows([stone(), expBook, highStone], { awaken: 0, maxAwaken: 3 })
  assert.deepEqual(rows.map((r) => r.itemId), ['item_hero_awaken_1', 'item_hero_awaken_2'])
})

test('前三阶只认初阶石，最后一阶只认高阶石（两块都列出来，但同一时刻只有一块点亮）', () => {
  const first = awakenRows([stone(), highStone], { awaken: 0, maxAwaken: 3 })
  assert.deepEqual(first.map((r) => [r.itemId, r.usable]),
    [['item_hero_awaken_1', true], ['item_hero_awaken_2', false]])
  assert.equal(first[1]?.reason, '这一阶用初阶觉醒石',
    '错石头的文案配反，等于告诉玩家两块都能用')

  const last = awakenRows([stone(), highStone], { awaken: 2, maxAwaken: 3 })
  assert.deepEqual(last.map((r) => [r.itemId, r.usable]),
    [['item_hero_awaken_1', false], ['item_hero_awaken_2', true]])
  assert.equal(last[0]?.reason, '最后一阶要用高阶觉醒石')
})

test('只有一阶可觉醒的武将（R 卡 awakenMax=1）：那一阶就是最后一阶 ⇒ 认高阶石', () => {
  // 这是服务端那条式子的直接后果，不是客户端的选择。抄错的人会把 R 卡做成"点不动"
  const rows = awakenRows([stone(), highStone], { awaken: 0, maxAwaken: 1 })
  assert.equal(rows[0]?.usable, false)
  assert.equal(rows[1]?.usable, true)
})

test('已达觉醒上限：两块都灰、不发，进度行与确认键都说实话', () => {
  const view = buildAwakenPick([stone(), highStone], { awaken: 3, maxAwaken: 3 },
    'item_hero_awaken_1')
  assert.equal(view.stageText, '已达觉醒上限 3 阶')
  assert.ok(view.rows.every((r) => !r.usable), '两块都该灰')
  assert.equal(view.rows.every((r) => r.reason === null), true,
    '满阶的原因写在进度那一行，不在每块石上重复一遍')
  assert.equal(view.canSend, false, '选了也不行：发出去只是把一次误点变成一条拒绝')
  assert.equal(view.sendText, '已达觉醒上限',
    '键上还写"先选觉醒石"就是把玩家引去点两块点不动的石')
})

test('进度行按 HeroView 那两个数拼：0→1 共 3 阶（不自己臆造上限）', () => {
  assert.equal(buildAwakenPick([stone()], { awaken: 0, maxAwaken: 3 }).stageText,
    '第 0 阶 → 第 1 阶（共 3 阶）')
})

test('没选中可用的石就不给发；灰掉的行点了也不算选中', () => {
  const items = [stone(), highStone]
  const stage = { awaken: 0, maxAwaken: 3 }
  assert.equal(buildAwakenPick(items, stage).canSend, false)
  assert.equal(buildAwakenPick(items, stage).sendText, '先选觉醒石')
  assert.equal(buildAwakenPick(items, stage, 'item_hero_awaken_2').selectedItemId, null,
    '高阶石这一阶不可用，点了也不该成为选中项')
  assert.equal(buildAwakenPick(items, stage, 'item_hero_awaken_1').canSend, true)
  assert.equal(buildAwakenPick(items, stage, 'item_hero_awaken_1').sendText, '确认觉醒')
})

test('一块觉醒石都没有时给说明行，且不发', () => {
  const view = buildAwakenPick([expBook], { awaken: 0, maxAwaken: 3 })
  assert.equal(view.rows.length, 0)
  assert.equal(view.emptyText, '背包里没有觉醒石')
  assert.equal(view.canSend, false)
})

// ---------- 镜像保险：客户端抄的这条规则，服务端源码里仍是同一句 ----------

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'item.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/item.json')
}

const SERVICE = 'server/game-web/src/main/java/com/ironoath/web/service/HeroAppService.java'

test('表里 AWAKEN_HERO 恰好两种、其中一种的稀有度就是这里镜像的那个值', () => {
  const rows = (JSON.parse(
    fs.readFileSync(path.join(repoRoot(), 'contract/config/item.json'), 'utf8'),
  ).rows as { id: string, rarity: string, effectKind: string }[])
    .filter((r) => r.effectKind === AWAKEN_KIND)
  const highTier = rows.filter((r) => r.rarity === FINAL_TIER_STONE_RARITY)
  assert.equal(rows.length, 2, `觉醒石变成 ${rows.length} 种了，"哪块对应哪一阶"要重新设计`)
  assert.equal(highTier.length, 1,
    `稀有度 ${FINAL_TIER_STONE_RARITY} 的觉醒石有 ${highTier.length} 块，客户端这套"至多一块点亮"就不成立了`)
  assert.equal(highTier[0]?.id, 'item_hero_awaken_2',
    '服务端拒绝文案里点名的就是这一行，改名要连着改文案')
})

test('服务端那三行判定还在原地（镜像失效时这条先红，而不是让玩家点不动）', () => {
  const src = fs.readFileSync(path.join(repoRoot(), SERVICE), 'utf8')
  for (const [label, pattern] of [
    ['finalTier 的定义', /boolean\s+finalTier\s*=\s*instance\.awaken\(\)\s*\+\s*1\s*==\s*maxAwaken/],
    ['高阶石的判别', new RegExp(`boolean\\s+highTierStone\\s*=\\s*itemCfg\\.rarity\\(\\)\\s*==\\s*ItemCfg\\.Rarity\\.${FINAL_TIER_STONE_RARITY}`)],
    ['两者不一致即拒绝', /if\s*\(\s*finalTier\s*!=\s*highTierStone\s*\)/],
  ] as Array<[string, RegExp]>) {
    assert.ok(pattern.test(src),
      `HeroAppService 里找不到「${label}」那一句 —— 服务端的觉醒规则改了，`
      + `game/hero/AwakenPick.ts 里那份镜像要跟着改（改完再更新本判据）`)
  }
})
