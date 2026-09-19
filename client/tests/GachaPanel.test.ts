/**
 * 职责：抽卡面板纯逻辑用例（抽卡入口 S2）。
 * 依赖：node:test + node:fs + game/gacha/GachaPanel（不碰 cc）。
 *
 * <p><b>每条钉的都是一个会做错的地方</b>：
 * ① 单抽够而十抽不够是常态，两个键各带自己的理由；
 * ② `balance >= cost` 而不是 `>`（刚好够是最常见的态）；
 * ③ 抽满过的池要灰掉并说"已抽满"，不是让玩家点出一条 RATE_LIMITED；
 * ④ 道具计价与资源计价各取各的余额，且都不许把行 id / 枚举原文印给玩家；
 * ⑤ 余额读不到时说"还没读到"，说 0 是把读侧故障伪装成玩家的错；
 * ⑥ 客户端不抄 gacha 表：唯一的 import 是协议类型与资源名真源，源码里没有写死的消耗数。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { TEN_DRAW_COUNT, buildGachaPanel, gachaRows } from '../assets/scripts/game/gacha/GachaPanel'
import type { BagItem, ResourceDetail } from '../assets/scripts/net/generated/BagProtocol'
import type { GachaPoolSummary } from '../assets/scripts/net/generated/HeroProtocol'

function pool(overrides: Partial<GachaPoolSummary> = {}): GachaPoolSummary {
  return {
    poolId: 'gacha_pool_newbie', name: '新手招募池', poolType: 'NEWBIE',
    costItemId: null, costItemName: null, costCount: 150,
    lifetimeLimit: 1, lifetimeDraws: 0, costResource: 'GOLD',
    ...overrides,
  }
}

function gold(current: number): ResourceDetail {
  return { type: 'GOLD', current, cap: 100000, protectedAmount: 0,
    perHour: 0, lastSettle: 0, full: false, breakdown: [] }
}

function chest(count: number): BagItem {
  return { itemId: 'item_chest_hero', name: '招募宝箱', type: 'MATERIAL', rarity: 'R',
    obtainFrom: '关卡', count, stackMax: 99, sortKey: 3,
    effectKind: 'OPEN_CHEST', effectTarget: null }
}

const limitedPool = (overrides: Partial<GachaPoolSummary> = {}): GachaPoolSummary => pool({
  poolId: 'gacha_pool_limited', poolType: 'LIMITED', costItemId: 'item_chest_hero',
  costItemName: '招募宝箱', costCount: 1, costResource: null, ...overrides,
})

test('单抽够、十抽不够：两个键各带自己的理由', () => {
  const rows = gachaRows([pool({ lifetimeLimit: 0 })], { resources: [gold(1000)], items: [] })
  assert.equal(rows[0]?.canDrawOnce, true, '150 ≤ 1000')
  assert.equal(rows[0]?.onceReason, null)
  assert.equal(rows[0]?.canDrawTen, false, '150×10=1500 > 1000')
  assert.equal(rows[0]?.tenReason, '还差 500 金币',
    '差多少要能照着去挣，只说"不够"等于没说')
})

test('余额刚好等于十抽成本：两个键都亮（判成 `>` 会让最常见的态点不动）', () => {
  const rows = gachaRows([pool({ lifetimeLimit: 0 })], { resources: [gold(1500)], items: [] })
  assert.equal(rows[0]?.canDrawTen, true)
  assert.equal(rows[0]?.tenReason, null)
})

test('新手池抽满过：两个键都灰、理由都是"已抽满"，而不是让玩家点出一条拒绝', () => {
  const rows = gachaRows([pool({ lifetimeDraws: 1 })], { resources: [gold(1000)], items: [] })
  assert.equal(rows[0]?.exhausted, true)
  assert.equal(rows[0]?.canDrawOnce, false)
  assert.equal(rows[0]?.onceReason, '这个号在该池已抽满')
  assert.equal(rows[0]?.tenReason, '这个号在该池已抽满')
  assert.equal(rows[0]?.limitText, '终身限抽 1 次 · 还剩 0 次')
})

test('还剩几次由下发的两个数相减：limit=10 draws=3 ⇒ 还剩 7 次', () => {
  const rows = gachaRows([pool({ lifetimeLimit: 10, lifetimeDraws: 3 })],
    { resources: [gold(1000)], items: [] })
  assert.equal(rows[0]?.limitText, '终身限抽 10 次 · 还剩 7 次')
  assert.equal(rows[0]?.exhausted, false)
})

test('不限次的池永不灰（limit=0 是"没有上限"，不是"上限为零"）', () => {
  const rows = gachaRows([pool({ lifetimeLimit: 0, lifetimeDraws: 999 })],
    { resources: [gold(1000)], items: [] })
  assert.equal(rows[0]?.limitText, '不限次')
  assert.equal(rows[0]?.canDrawOnce, true)
})

test('道具计价的池：消耗名随行下发，余额取背包那一行', () => {
  const rows = gachaRows([limitedPool()], { resources: [gold(9999)], items: [chest(3)] })
  assert.equal(rows[0]?.costText, '1 招募宝箱')
  assert.equal(rows[0]?.onceReason, null, '3 个宝箱够抽一次')
  assert.equal(rows[0]?.canDrawTen, false, '只有 3 个宝箱，抽不了十次')
  assert.equal(rows[0]?.tenReason, '还差 7 招募宝箱')
})

test('背包里没有那一行道具 ⇒ 余额按 0 算（服务端不列余数为 0 的道具行）', () => {
  const rows = gachaRows([limitedPool({ lifetimeLimit: 0 })], { resources: [], items: [] })
  assert.equal(rows[0]?.canDrawOnce, false)
  assert.equal(rows[0]?.onceReason, '还差 1 招募宝箱')
})

test('画面上不许出现行 id 与资源枚举原文（#255/#268/#281/#291 同族）', () => {
  const view = buildGachaPanel([pool({ lifetimeLimit: 0 }),
    pool({ poolId: 'gacha_pool_standard', name: '标准招募池', lifetimeLimit: 0 })],
  { resources: [gold(1000)], items: [] })
  // 只量**要画出来的那些字**：rows 里的 poolId 是发请求用的，不是给玩家看的
  const drawn = [
    ...view.rows.map(r => `${r.name}|${r.costText}|${r.limitText}|${r.onceReason ?? ''}`
      + `|${r.tenReason ?? ''}`),
    view.balanceText ?? '', view.singleText, view.tenText,
  ].join(' ')
  for (const banned of ['GOLD', 'item_chest_hero', 'gacha_pool_', 'NEWBIE']) {
    assert.ok(!drawn.includes(banned), `画面上出现了 ${banned}`)
  }
  assert.ok(drawn.includes('金币'))
})

test('资源那一行读不到 ⇒ 说"余额还没读到"，不谎称还差 N', () => {
  const rows = gachaRows([pool({ lifetimeLimit: 0 })], { resources: [], items: [] })
  assert.equal(rows[0]?.canDrawOnce, false)
  assert.equal(rows[0]?.onceReason, '余额还没读到',
    '把读侧故障说成"你还差 150"，玩家会去挣一笔本来不需要挣的钱')
  assert.equal(rows[0]?.tenReason, '余额还没读到')
})

test('顺序照服务端下发；选中读不到的池退回第一行', () => {
  const pools = [pool({ lifetimeLimit: 0 }),
    pool({ poolId: 'gacha_pool_standard', name: '标准招募池', lifetimeLimit: 0 })]
  const view = buildGachaPanel(pools, { resources: [gold(1000)], items: [] }, 'gacha_pool_not_there')
  assert.deepEqual(view.rows.map((r) => r.poolId), ['gacha_pool_newbie', 'gacha_pool_standard'])
  assert.equal(view.selectedPoolId, 'gacha_pool_newbie')
})

test('余额那一行只报选中池那一档计价，十抽文本按 costCount × 10', () => {
  const view = buildGachaPanel([pool({ lifetimeLimit: 0 })],
    { resources: [gold(1000)], items: [] }, 'gacha_pool_newbie')
  assert.equal(view.balanceText, '1000 金币')
  assert.equal(view.tenText, `抽十次 · ${String(150 * TEN_DRAW_COUNT)} 金币`)
  assert.equal(view.singleText, '抽一次 · 150 金币')
})

test('一个池都没有时不崩，键上写"先选一个卡池"', () => {
  const view = buildGachaPanel([], { resources: [gold(1000)], items: [] })
  assert.deepEqual(view.rows, [])
  assert.equal(view.selected, null)
  assert.equal(view.singleText, '先选一个卡池')
  assert.equal(view.tenText, '先选一个卡池')
})

test('抽过的结果行：新武将与转碎片分开说，保底要标出来（不写就等于让玩家以为抽了个没用的）', () => {
  const draw = {
    results: [
      { heroId: 'hero_ssr_01', name: '李劲', rarity: 'SSR', isNew: true, isPity: true, fragments: 0 },
      { heroId: 'hero_sr_01', name: '程远', rarity: 'SR', isNew: false, isPity: false, fragments: 20 },
    ],
    ssrPityCounter: 0, srPityCounter: 3, fragmentsAwarded: 20,
    costItemId: null, costCount: 1500, seed: 1, serverNow: 0, costResource: 'GOLD',
  }
  const view = buildGachaPanel([pool({ lifetimeLimit: 0, lifetimeDraws: 10 })],
    { resources: [gold(1000)], items: [] }, 'gacha_pool_newbie', null, draw as never)
  assert.deepEqual(view.resultTexts, ['李劲 · 新武将（保底）', '程远 · 转 20 碎片'])
  assert.ok(!JSON.stringify(view.resultTexts).includes('hero_'), '结果行不许印行 id')
})

test('还没抽过时结果那一块是 null，不是空数组（空数组会让面板画一个空框）', () => {
  const view = buildGachaPanel([pool({ lifetimeLimit: 0 })],
    { resources: [gold(1000)], items: [] })
  assert.equal(view.resultTexts, null)
})

// ---------- 镜像保险：客户端不抄表 ----------

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'gacha.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/gacha.json')
}

const OWN_LOGIC = 'client/assets/scripts/game/gacha/GachaPanel.ts'
const SERVICE = 'server/game-web/src/main/java/com/ironoath/web/service/GachaAppService.java'

test('本纯逻辑唯一的 import 是协议类型与资源名真源，且代码里没有写死的消耗数', () => {
  const src = fs.readFileSync(path.join(repoRoot(), OWN_LOGIC), 'utf8')
  const imports = (src.match(/^import[^\n]*$/gm) ?? []).map(l => l.replace(/\s+/g, ' '))
  assert.equal(imports.length, 3, `多出来的 import 就是第二份真相：${imports.join(' | ')}`)
  assert.ok(imports.every(l => l.includes('net/generated') || l.includes('ui/ResourceNames')),
    '只许依赖协议类型与资源名那一份真源')
  // 只看**代码**：注释里举的例子（`金币 1200` 就是表里的单抽价）不该被这条判据当成抄表
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(!/costCount\s*[=<>]=?\s*\d/.test(code), '与写死的数字比，就是抄了表')
  assert.ok(!/\b(150|1200)\b/.test(code), '代码里出现表里的消耗数，表一改就"明明够却说不够"')
})

test('服务端三处还在原地：收钱用 costCount × count、超限读 lifetimeDraws、池列表带已抽次数', () => {
  const src = fs.readFileSync(path.join(repoRoot(), SERVICE), 'utf8')
  for (const [label, pattern] of [
    ['十抽按同一个式子收钱', /pool\.costCount\(\)\s*\*\s*req\.count\(\)/],
    ['超限判定读已抽次数', /state\.lifetimeDraws\(\)\s*\+\s*req\.count\(\)\s*>\s*pool\.lifetimeLimit\(\)/],
    ['池列表下发已抽次数', /\.map\(GachaState::lifetimeDraws\)/],
  ] as Array<[string, RegExp]>) {
    assert.ok(pattern.test(src),
      `GachaAppService 里找不到「${label}」那一句 —— 服务端改了，`
      + `game/gacha/GachaPanel.ts 这份"能不能抽"的判据要重新对`)
  }
})
