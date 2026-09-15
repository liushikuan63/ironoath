/**
 * 职责：QuestPanel 的单测 —— B12 §1 的任务列表组装，以及收口清单 #98 的「三选一送将」流程。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯三类错误：
 * <ol>
 *   <li><b>客户端自己算「能不能领」</b>。前置链与幂等都在服务端，客户端算第二遍迟早分叉，
 *       而分叉的表现是「按钮亮着但点下去被拒」</li>
 *   <li><b>三选一被绕过</b>。带候选的任务不选就发请求会被服务端拒 —— 所以「先弹选择」这一层
 *       必须有用例钉住，否则某次重构把它删掉，玩家看到的是「点了领奖就报错」</li>
 *   <li><b>无候选的任务被带上 heroChoice</b>。服务端也会拒（多传同样不放过），
 *       所以这条同样要能失败</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildClaimSummary, buildQuestList, candidateLabel, chosenClaimReq, claimIntentOf,
} from '../assets/scripts/game/quest/QuestPanel'
import type { QuestClaimResp, QuestListResp, QuestView } from '../assets/scripts/net/generated/QuestProtocol'
import type { QuestRow } from '../assets/scripts/game/quest/QuestPanel'

/** 取列表的某一行；下标越界时直接失败，免得断言落在 undefined 上假装通过。 */
function rowAt(resp: QuestListResp, index: number): QuestRow {
  const row = buildQuestList(resp).rows[index]
  assert.ok(row !== undefined, `第 ${index} 行必须存在`)
  return row
}

function view(overrides: Partial<QuestView> = {}): QuestView {
  return {
    questId: 'quest_side_01',
    name: '采集木材',
    type: 'SIDE',
    goalType: 'GATHER_RESOURCE',
    goalTarget: 'WOOD',
    goalValue: 5000,
    current: 2500,
    complete: false,
    claimed: false,
    claimable: false,
    locked: false,
    preQuestId: null,
    heroChoices: [],
    ...overrides,
  }
}

test('任务列表：四态（未解锁/进行中/可领取/已领取）各自有文案，且顺序照搬服务端', () => {
  const resp: QuestListResp = {
    quests: [
      view({ questId: 'a', locked: true, preQuestId: 'q0' }),
      view({ questId: 'b' }),
      view({ questId: 'c', complete: true, claimable: true }),
      view({ questId: 'd', complete: true, claimed: true }),
    ],
    claimableCount: 1,
    serverNow: 1_780_000_000_000,
  }
  const list = buildQuestList(resp)

  assert.deepEqual(list.rows.map(r => r.questId), ['a', 'b', 'c', 'd'], '行顺序必须照搬服务端')
  const r0 = rowAt(resp, 0)
  const r1 = rowAt(resp, 1)
  const r2 = rowAt(resp, 2)
  const r3 = rowAt(resp, 3)
  assert.equal(r0.statusText, '未解锁')
  assert.equal(r0.lockedHint, '完成后解锁：前置任务',
    '灰掉的行必须说明为什么。这份夹具里 q0 不在列表中，所以只能退成泛指 —— '
    + '但**绝不能退成配置 id**：把 quest_main_01 印到玩家眼前等于让界面说黑话')
  assert.equal(r1.statusText, '进行中')
  assert.equal(r1.progressText, '2,500/5,000')
  assert.equal(r2.statusText, '可领取')
  assert.equal(r2.progressText, '已完成')
  assert.equal(r3.statusText, '已领取')
  assert.equal(list.claimableText, '1 个奖励可领取')

  assert.equal(buildQuestList({ quests: [], claimableCount: 0, serverNow: 1 }).claimableText, null,
'没有可领的就不该显示徽标文案（空数组与 null 是两种状态，别用「0 个」凑）')
})

test('未解锁行显示前置任务的「名字」而不是配置 id（微信模拟器实测抓到）', () => {
  // 模拟器里那一行原本是「完成后解锁：quest_main_01」—— 玩家看到的是表里的编号。
  // 名字本来就在同一次响应里（列表连未解锁的行一起下发），所以这一步只是把服务端
  // 已经给的数据换个字段用，不是客户端查配置表、也不是翻译（铁律 2 仍然成立）。
  const resp: QuestListResp = {
    quests: [
      view({ questId: 'quest_main_01', name: '筑起第一堵墙' }),
      view({ questId: 'quest_main_02', name: '囤积粮草', locked: true, preQuestId: 'quest_main_01' }),
    ],
    claimableCount: 0,
    serverNow: 1,
  }
  const [first, second] = buildQuestList(resp).rows
  assert.ok(first !== undefined && second !== undefined, '两行都该在')

  assert.equal(second.lockedHint, '完成后解锁：筑起第一堵墙')
  assert.equal(second.lockedHint.includes('quest_main_01'), false,
    '界面上不许出现内部编号：' + second.lockedHint)
  assert.equal(first.lockedHint, null, '没锁的行不该带这句')
})

test('可领取判定完全取服务端：客户端不看 current/goalValue 自己算', () => {
  // 进度看起来够了、但服务端没标 claimable（例如前置没领）⇒ 客户端不许自作主张点亮按钮
  const row = rowAt({
    quests: [view({ current: 5000, goalValue: 5000, complete: true, claimable: false, locked: true })],
    claimableCount: 0,
    serverNow: 1,
  }, 0)
  assert.equal(row.claimable, false, '完成但不可领（前置未领）不能被客户端算成可领')
})

test('三选一：带候选的任务必须先弹选择，不能直接领', () => {
  const row = rowAt({
    quests: [view({ questId: 'quest_main_01', complete: true, claimable: true,
      heroChoices: [
        { heroId: 'hero_sr_01', name: '卫无咎' },
        { heroId: 'hero_sr_02', name: '沈砚秋' },
        { heroId: 'hero_sr_03', name: '崔明烛' },
      ] })],
    claimableCount: 1,
    serverNow: 1,
  }, 0)

  const intent = claimIntentOf(row, row.title)
  assert.equal(intent.kind, 'choose', '有候选就必须先弹选择 —— 直接领会被服务端拒')
  if (intent.kind === 'choose') {
    assert.deepEqual(intent.prompt.candidates.map(c => c.heroId),
      ['hero_sr_01', 'hero_sr_02', 'hero_sr_03'])
    assert.deepEqual(intent.prompt.candidates.map(c => c.name),
      ['卫无咎', '沈砚秋', '崔明烛'], '名字随 id 成对下发（两条平行数组迟早不同长）')
    assert.equal(intent.prompt.questId, 'quest_main_01')
  }
})

test('三选一：选定之后才构造请求体，且选中的必须在候选里', () => {
  const prompt = { questId: 'quest_main_01', title: '主线 · 筑起第一堵墙',
    candidates: [{ heroId: 'hero_sr_01', name: '卫无咎' }, { heroId: 'hero_sr_02', name: '沈砚秋' }] }
  const req = chosenClaimReq(prompt, 'hero_sr_02')
  assert.equal(req.questId, 'quest_main_01')
  assert.equal(req.heroChoice, 'hero_sr_02')

  assert.throws(() => chosenClaimReq(prompt, 'hero_ssr_01'),
    /不在候选里/, '界面只该给出候选里的选项；放行一个候选外的 id 会换来服务端的一次拒绝')
})

test('无候选的任务：直接领，且不带 heroChoice（多传也会被拒）', () => {
  const row = rowAt({
    quests: [view({ questId: 'quest_side_01', complete: true, claimable: true })],
    claimableCount: 1,
    serverNow: 1,
  }, 0)

  const intent = claimIntentOf(row, row.title)
  assert.equal(intent.kind, 'claim')
  if (intent.kind === 'claim') {
    assert.equal(intent.heroChoice, null, '没有候选就是 null —— 传空串或随便挑一个都会被服务端拒')
  }
})

test('不可领取时不发请求：noop 带原因，而不是让灰按钮打出一次注定失败的请求', () => {
  const row = rowAt({
    quests: [view({ questId: 'q', claimed: true, complete: true })],
    claimableCount: 0,
    serverNow: 1,
  }, 0)

  const intent = claimIntentOf(row, row.title)
  assert.equal(intent.kind, 'noop')
  if (intent.kind === 'noop') {
    assert.match(intent.reason, /已领取/)
  }
})

test('领取回执：奖励明细用服务端给的名字，整卡武将单独挑出来给动效用', () => {
  const resp: QuestClaimResp = {
    questId: 'quest_main_01',
    rewards: [
      { type: 'HERO', id: 'hero_sr_01', count: 1, name: '卫无咎' },
      { type: 'RESOURCE', id: 'GOLD', count: 100, name: '金币' },
    ],
    claimableCount: 2,
    serverNow: 1,
  }
  const summary = buildClaimSummary(resp)

  assert.deepEqual([...summary.rewardLines], ['卫无咎 ×1', '金币 ×100'],
    '名字必须用服务端解析好的（客户端不得自行翻译）')
  assert.deepEqual([...summary.grantedHeroIds], ['hero_sr_01'], '整卡要能被界面单独拿出来播动效')
  assert.equal(summary.remainingText, '还有 2 个奖励可领取')

  assert.equal(buildClaimSummary({ ...resp, claimableCount: 0 }).remainingText, null,
    '领完最后一条就不该再提示「还有 0 个」')
})

test('候选展示名直接用服务端那份，不自己翻译也不回落 id', () => {
  assert.equal(candidateLabel({ heroId: 'hero_sr_01', name: '卫无咎' }), '卫无咎',
    '名字由服务端从 hero 表解析；客户端自己拼会与战报、客服工单里的称呼对不上')
})
