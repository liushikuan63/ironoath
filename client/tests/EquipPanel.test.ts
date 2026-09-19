/**
 * 职责：装备实例页读侧的纯逻辑用例（V03-b-S1，台账 #265）。
 * 依赖：node:test + game/equip/EquipPanel（不碰 cc）。
 *
 * <p><b>四处最容易做假的地方</b>：① `canForge`/`blockReason` 只能照抄服务端；
 * ② 铁耗与三维用服务端给的数（定点走 format 而不是 percent）；③ `wornByHeroId` 不许印成 id；
 * ④ 顺序原样照抄，不重排。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildEquipPanel, costTextOf, forgeTextOf, rarityLabel, slotLabel, statsTextOf,
} from '../assets/scripts/game/equip/EquipPanel'
import type { EquipInstanceListView, EquipInstanceView } from '../assets/scripts/net/generated/EquipProtocol'

function instance(overrides: Partial<EquipInstanceView> = {}): EquipInstanceView {
  return {
    uid: 'eq-1', equipId: 'equip_sword_01', name: '铁脊剑', slot: 'WEAPON', rarity: 'R',
    forgeLevel: 2, forgeMax: 20, mightFixed: 120_000, commandFixed: 80_000, wisdomFixed: 0,
    nextCostIron: 480, canForge: true, blockReason: 'NONE', wornByHeroId: null,
    ...overrides,
  }
}

function list(overrides: Partial<EquipInstanceListView> = {}): EquipInstanceListView {
  return { instances: [instance()], serverNow: 1_700_000_000_000, ...overrides }
}

test('列表没拉回来时画一句说明，不画一个假装备栏', () => {
  const view = buildEquipPanel(null)
  assert.equal(view.rows.length, 0)
  assert.match(view.noticeText ?? '', /还没拉回来/)
})

test('拉不到时把服务端给的理由原样放上说明行（与科技/赛季同一条纪律）', () => {
  assert.equal(buildEquipPanel(null, '请求太频繁，稍后再试').noticeText, '请求太频繁，稍后再试')
  const stale = buildEquipPanel(list(), '服务繁忙')
  assert.equal(stale.noticeText, '服务繁忙')
  assert.equal(stale.rows.length, 1, '拉不到不等于装备没了：上一次那份留着')
})

test('一行把强化等级、三维、铁耗都摊开，定点走 format 不是百分比', () => {
  const row = buildEquipPanel(list()).rows[0]
  assert.equal(row?.name, '铁脊剑')
  assert.equal(row?.slotText, '武器')
  assert.equal(row?.forgeText, '强化 +2 / 20')
  assert.equal(row?.statsText, '武力 +12 · 统率 +8', '120000 定点 = 12，不是 1200%')
  assert.equal(row?.costText, '强化消耗 铁矿 480')
})

test('满级那件：不显示铁耗，原因写"已满级"', () => {
  const maxed = instance({ forgeLevel: 20, canForge: false, blockReason: 'MAX_LEVEL', nextCostIron: 0 })
  assert.equal(costTextOf(maxed), null, '满级时成本是 0，写出来只会让人以为还能强化')
  const row = buildEquipPanel(list({ instances: [maxed] })).rows[0]
  assert.equal(row?.reasonText, '已满级')
  assert.equal(row?.forgeText, '强化 +20 / 20')
})

test('两条拒绝原因各有各的话，且不逐字照抄枚举名', () => {
  const low = buildEquipPanel(list({
    instances: [instance({ canForge: false, blockReason: 'IRON_LOW' })],
  })).rows[0]
  assert.equal(low?.reasonText, '铁矿不足')
  assert.equal(low?.costText, '强化消耗 铁矿 480', '被拒的行照样把铁耗摊开：玩家要据此决定去攒什么')
  const ok = buildEquipPanel(list()).rows[0]
  assert.equal(ok?.reasonText, null, '能强化时不写一句"没问题"占地方')
})

test('穿没穿只说"已装备/未装备"，绝不把 heroId 印到面板上（#255 的同一根因）', () => {
  const worn = buildEquipPanel(list({ instances: [instance({ wornByHeroId: 'hero_guanyu' })] })).rows[0]
  assert.equal(worn?.wornText, '已装备')
  assert.equal(worn?.worn, true)
  assert.equal(/hero_/.test(worn?.wornText ?? ''), false, '印出的是 id 不是名字')
  const idle = buildEquipPanel(list()).rows[0]
  assert.equal(idle?.wornText, '未装备')
})

test('汇总行数得清：已装备几件 / 共几件', () => {
  const view = buildEquipPanel(list({
    instances: [instance({ uid: 'a', wornByHeroId: 'hero_1' }), instance({ uid: 'b' }), instance({ uid: 'c' })],
  }))
  assert.equal(view.summaryText, '已装备 1 / 共 3 件')
})

test('空列表写实话"还没有装备"，不写"正在载入"（探针截图里抓到的）', () => {
  const view = buildEquipPanel(list({ instances: [] }))
  assert.equal(view.rows.length, 0)
  assert.equal(view.summaryText, '已装备 0 / 共 0 件')
  assert.equal(view.noticeText, '还没有装备')
  assert.equal(/正在载入/.test(view.noticeText ?? ''), false, '"正在载入"是给"还没拉回来"用的')
})

test('顺序照抄服务端，不按"未装备排前面"重排', () => {
  const view = buildEquipPanel(list({
    instances: [
      instance({ uid: 'worn-first', wornByHeroId: 'hero_1' }),
      instance({ uid: 'idle-second' }),
    ],
  }))
  assert.deepEqual(view.rows.map((r) => r.uid), ['worn-first', 'idle-second'])
})

test('槽位是玩家语言；稀有度保留 N/R/SR/SSR（仓库里没有中文口径可抄，不发明术语）', () => {
  assert.deepEqual(
    (['WEAPON', 'ARMOR', 'MOUNT', 'ACCESSORY'] as const).map((s) => slotLabel(s)),
    ['武器', '护甲', '坐骑', '饰品'],
  )
  assert.equal(slotLabel('SHIELD' as never), 'SHIELD', '表里没有的退回原文，不显示空行')
  assert.deepEqual((['N', 'R', 'SR', 'SSR'] as const).map((r) => rarityLabel(r)), ['N', 'R', 'SR', 'SSR'])
})

test('三维全为 0 时写"无属性加成"，不画一个空的加号', () => {
  const plain = instance({ mightFixed: 0, commandFixed: 0, wisdomFixed: 0 })
  assert.equal(statsTextOf(plain), '无属性加成')
  assert.equal(forgeTextOf(plain), '强化 +2 / 20')
})
