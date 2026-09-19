/**
 * 职责：碎片合成武将的纯逻辑用例（V03-d 第六条养成线）。
 * 依赖：node:test + node:fs + game/hero/HeroCompose（不碰 cc）。
 *
 * <p><b>每条钉的都是一个会做错的地方</b>：
 * ① 门槛与余额只能取服务端下发的那两个数（抄 hero_rarity 表 ⇒ 表一改就见人说"够了"）；
 * ② `count >= need` 而不是 `>`（刚好凑够是最常见的态，判反了玩家永远点不动）；
 * ③ "还差几片"= need − count（差一片写成差两片，玩家会去多刷一份）；
 * ④ 不够的行照样出现并写明原因（藏掉 = 告诉玩家"这个武将不存在"）；
 * ⑤ 灰行点了不算选中；
 * ⑥ 余额只报一次（钱包在抬头，行上不重复）；
 * ⑦ 镜像保险：服务端那两处（排除已拥有、按稀有度扣 composeFragment）还在原地。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { buildComposeView, composeRows } from '../assets/scripts/game/hero/HeroCompose'
import type { FragmentView } from '../assets/scripts/net/generated/HeroProtocol'

function purse(overrides: Partial<FragmentView> = {}): FragmentView {
  return {
    itemId: 'item_mat_hero_frag_sr', name: 'SR 武将碎片', count: 12,
    composeFragment: 50,
    candidates: [{ heroId: 'hero_sr_01', name: '程远' }, { heroId: 'hero_sr_02', name: '沈牧' }],
    ...overrides,
  }
}

const ssrPurse = (count: number): FragmentView => purse({
  itemId: 'item_mat_hero_frag_ssr', name: 'SSR 武将碎片', composeFragment: 80,
  count, candidates: [{ heroId: 'hero_ssr_01', name: '李劲' }],
})

// ---------- 够不够 ----------

test('刚好凑够：那一行点亮（判成 `>` 会让玩家永远点不动最常见的态）', () => {
  const rows = composeRows([purse({ count: 50 })])
  assert.equal(rows.length, 2)
  assert.ok(rows.every((r) => r.usable), '余额 === 门槛就该可用')
  assert.equal(rows[0]?.detailText, 'SR 武将碎片 需 50 片 · 碎片已够')
})

test('差一片：写的就是"还差 1 片"，且行上不重复报余额', () => {
  const rows = composeRows([purse({ count: 49 })])
  assert.equal(rows[0]?.usable, false)
  assert.equal(rows[0]?.detailText, 'SR 武将碎片 需 50 片 · 还差 1 片',
    '差几片算错，玩家会去多刷一份碎片')
  assert.ok(!rows[0]?.detailText.includes('49'),
    '余额是钱包那一行的数，行上再报一次就是一屏两个真相')
})

test('余额为 0 也照样列出这一档（"没攒过"要看得见，不是藏起来）', () => {
  const rows = composeRows([purse({ count: 0 })])
  assert.equal(rows.length, 2, '一档没攒过不等于这一档没有武将可合成')
  assert.equal(rows[0]?.detailText, 'SR 武将碎片 需 50 片 · 还差 50 片')
})

test('门槛 0（某一档做成免费）：不炸、直接可用', () => {
  const rows = composeRows([purse({ composeFragment: 0, count: 0 })])
  assert.equal(rows[0]?.usable, true)
  assert.equal(rows[0]?.detailText, 'SR 武将碎片 需 0 片 · 碎片已够')
})

test('余额远大于门槛：不缺就说已够，不报"还差负数片"', () => {
  const rows = composeRows([purse({ count: 500 })])
  assert.equal(rows[0]?.usable, true)
  assert.ok(!/-\d/.test(rows[0]?.detailText ?? ''), rows[0]?.detailText)
})

// ---------- 摊平与顺序 ----------

test('跨档摊平：能合成的排前面，两组内部各自照服务端顺序；每行吃自己那一档的门槛', () => {
  // SSR 那一档故意不够（10/80），而它排在**前面** —— 考验的就是"够的浮上来"这一道
  const rows = composeRows([ssrPurse(10), purse({ count: 60 })])
  assert.deepEqual(rows.map((r) => r.heroId), ['hero_sr_01', 'hero_sr_02', 'hero_ssr_01'],
    '灰行把能合成的那名挤到屏外，等于让玩家对着一屏"还差 80 片"找不到能点的那一个')
  assert.deepEqual(rows.map((r) => r.usable), [true, true, false])
  assert.equal(rows[2]?.detailText, 'SSR 武将碎片 需 80 片 · 还差 70 片')
})

test('全都不够时顺序原样保留（没有可浮上来的，就不该出现第二次重排）', () => {
  const rows = composeRows([ssrPurse(10), purse({ count: 3 })])
  assert.deepEqual(rows.map((r) => r.heroId),
    ['hero_ssr_01', 'hero_sr_01', 'hero_sr_02'], '两组都灰 ⇒ 保持服务端那份档序 × 行序')
})

test('某一档没有候选武将（全已拥有 / 这一档没投放碎片）：不占行也不报错', () => {
  const rows = composeRows([purse({ candidates: [] }), purse({ name: 'R 武将碎片' })])
  assert.equal(rows.length, 2, '空的那档不该冒出行')
  assert.ok(rows.every((r) => r.detailText.startsWith('R 武将碎片')), '行只从有候选的档摊出来')
})

test('行与总览都不印内部编号：itemId 与 heroId 不许出现在画面上', () => {
  const view = buildComposeView([purse()])
  for (const row of view.rows) {
    for (const banned of ['item_mat_hero_frag', 'hero_sr_0']) {
      assert.ok(!row.detailText.includes(banned), `${row.detailText} 里出现了 ${banned}`)
      assert.ok(!row.name.includes(banned))
    }
  }
  assert.ok(!view.summaryText.includes('hero_sr_0'))
})

// ---------- 选中与发送 ----------

test('灰行点了不算选中：canSend 为 false，键上写"先选一名武将"', () => {
  const view = buildComposeView([purse({ count: 49 })], 'hero_sr_01')
  assert.equal(view.selectedHeroId, null, '不够的行不能成为选中项')
  assert.equal(view.canSend, false)
  assert.equal(view.sendText, '先选一名武将')
})

test('选中可用的那一行：确认键点亮，键上写"确认合成"', () => {
  const view = buildComposeView([purse({ count: 60 })], 'hero_sr_02')
  assert.equal(view.selectedHeroId, 'hero_sr_02')
  assert.equal(view.canSend, true)
  assert.equal(view.sendText, '确认合成')
})

test('总览行把"几名可合成"与"几名凑够"分开报（合成一个数就看不出卡在哪）', () => {
  const view = buildComposeView([purse({ count: 12 }), ssrPurse(80)])
  assert.equal(view.summaryText, '3 名武将可以合成 · 其中 1 名碎片已凑够')
})

test('一个候选都没有：给说明行、不发，且键上不再写"先选一名武将"', () => {
  const view = buildComposeView([purse({ candidates: [] })])
  assert.equal(view.rows.length, 0)
  assert.equal(view.emptyText, '未拥有的武将里，没有可以用碎片合成的')
  assert.equal(view.canSend, false)
  assert.equal(view.sendText, '暂无可合成武将',
    '没有候选还写"先选一名"，玩家会在空弹层里挨个点')
})

// ---------- 镜像保险：客户端不抄表，但要判服务端那两处还在 ----------

function repoRoot(): string {
  let dir = process.cwd()
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(dir, 'contract', 'config', 'hero_rarity.json'))) return dir
    dir = path.resolve(dir, '..')
  }
  throw new Error('找不到 contract/config/hero_rarity.json')
}

const SERVICE = 'server/game-web/src/main/java/com/ironoath/web/service/HeroAppService.java'
const OWN_LOGIC = 'client/assets/scripts/game/hero/HeroCompose.ts'

test('本纯逻辑不读配置表：唯一的 import 就是生成的协议类型，文案里没有写死的门槛', () => {
  const src = fs.readFileSync(path.join(repoRoot(), OWN_LOGIC), 'utf8')
  const imports = src.match(/^import[^\n]*$/gm) ?? []
  assert.equal(imports.length, 1, `多出来的 import 就是第二份真相：${imports.join(' | ')}`)
  assert.ok(imports[0].includes('net/generated/HeroProtocol'),
    '门槛、余额、候选只能来自 FragmentView —— 抄 hero_rarity 表的后果是表一改就见人说"够了"')
  assert.ok(!/需 \d+ 片/.test(src), '文案里写死了门槛数字')
})

test('服务端两处还在原地：候选排除已拥有、门槛按稀有度查表', () => {
  const src = fs.readFileSync(path.join(repoRoot(), SERVICE), 'utf8')
  for (const [label, pattern] of [
    ['候选排除已拥有', /!roster\.owns\(hero\.id\(\)\)/],
    ['随行下发的门槛', /rarityEconomy\(rarity\)\.composeFragment\(\)/],
    ['合成实际扣的数', /rarityEconomy\(cfg\.rarity\(\)\)\.composeFragment\(\)/],
  ] as Array<[string, RegExp]>) {
    assert.ok(pattern.test(src),
      `HeroAppService 里找不到「${label}」那一句 —— 服务端改了，`
      + `FragmentView 的门槛/候选口径要重新对（客户端这份视图只在"下发的数"上做判断）`)
  }
})
