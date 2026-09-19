/**
 * 职责：集结面板的纯逻辑用例（V02-S1）。
 * 依赖：node:test + game/social/RallyPanel（不碰 cc）。
 *
 * <p><b>这些用例盯的是四处最容易做假的地方</b>：① 倒计时**只用服务端两个时刻相减**（铁律 5）；
 * ② 「我参没参」是拿自己的 id 去对一份**已下发的名单**，不是重新判规则；
 * ③ 发起人给的是「取消」不是「退出」—— 服务端是两条路，合并成一个按钮会让发起人以为只是自己走了；
 * ④ 满员且我没参时**不给按钮但给原因**（没有文案的灰按钮会被当成坏了）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  actionPath, buildRallyPanel, buildRallyRow, coordText, remainTextOf, scopeLabel, targetLabel,
} from '../assets/scripts/game/social/RallyPanel'
import type { RallyView } from '../assets/scripts/net/generated/SocialProtocol'

const ME = 'P-me'
const NOW = 1_700_000_000_000

function rally(overrides: Partial<RallyView> = {}): RallyView {
  return {
    rallyId: 'rally-1', scope: 'SQUAD', groupId: 'squad-1', initiatorId: 'P-leader',
    targetCoord: { x: 433, y: 95 }, targetType: 'MONSTER', maxMembers: 10,
    joinedCount: 3, totalTroops: 12400, prepareUntil: NOW + 155_000, departAt: NOW + 155_000,
    status: 'PREPARING', members: ['P-leader', 'P-2', 'P-3'], serverNow: NOW,
    ...overrides,
  } as RallyView
}

test('倒计时只用服务端两个时刻相减（铁律 5），且不给负数', () => {
  assert.equal(remainTextOf(rally()), '准备还剩 2 分 35 秒')
  assert.equal(remainTextOf(rally({ prepareUntil: NOW + 42_000 })), '准备还剩 42 秒')
  assert.equal(remainTextOf(rally({ prepareUntil: NOW - 1 })), '即将出发')
  assert.equal(remainTextOf(rally({ prepareUntil: NOW })), '即将出发')
})

test('「我参没参」拿自己的 id 对已下发的名单：不在名单里就是没参', () => {
  const notMine = buildRallyRow(rally(), ME)
  assert.equal(notMine.mine, false)
  assert.equal(notMine.action, 'join')
  assert.equal(notMine.actionText, '加入')

  const mine = buildRallyRow(rally({ members: ['P-leader', ME, 'P-3'] }), ME)
  assert.equal(mine.mine, true)
  assert.equal(mine.action, 'quit')
  assert.equal(mine.actionText, '退出')
})

test('发起人给「取消」而不是「退出」：服务端是两条路，合并会让发起人以为只是自己走了', () => {
  const leader = buildRallyRow(rally({ initiatorId: ME, members: [ME, 'P-2'] }), ME)
  assert.equal(leader.initiatedByMe, true)
  assert.equal(leader.action, 'cancel')
  assert.equal(leader.actionText, '取消集结')
  assert.equal(actionPath(leader.action), '/rally/cancel')
})

test('满员且我没参：不给按钮，但把那句原因说出来', () => {
  const full = buildRallyRow(rally({ joinedCount: 10, maxMembers: 10, members: ['P-leader'] }), ME)
  assert.equal(full.action, null)
  assert.equal(full.actionText, null)
  assert.equal(full.blockedReason, '已满 10 人')
})

test('拿不到自己的 id 时一律按「没参」处理：不知道我是谁就别给「退出」', () => {
  const row = buildRallyRow(rally({ initiatorId: 'P-leader', members: ['P-leader'] }), '')
  assert.equal(row.mine, false)
  assert.equal(row.initiatedByMe, false)
  assert.equal(row.action, 'join')
})

test('三种动作三条路，绝不共用一个端点（服务端就是三个）', () => {
  assert.equal(actionPath('join'), '/rally/join')
  assert.equal(actionPath('quit'), '/rally/quit')
  assert.equal(actionPath('cancel'), '/rally/cancel')
  assert.equal(actionPath(null), null)
})

test('行文案是玩家语言：层级、目标类型、坐标、人数与兵力都来自响应', () => {
  const row = buildRallyRow(rally(), ME)
  assert.equal(row.scopeText, '小队集结')
  assert.equal(row.targetText, '目标：野外怪 (433,95)')
  assert.equal(row.partyText, '已加入 3/10 人 · 兵力 12400')
  assert.equal(coordText({ x: 1, y: 2 }), '(1,2)')
  assert.equal(scopeLabel('ALLIANCE'), '联盟集结')
  assert.equal(scopeLabel('NATION'), '国家集结')
})

test('目标类型的五个取值都有中文名（缺一个就会在界面上露出半英文）', () => {
  assert.deepEqual(
    (['EMPTY', 'MONSTER', 'RESOURCE', 'PLAYER_CITY', 'ALLIANCE_BUILDING'] as const)
      .map((t) => targetLabel(t)),
    ['空地', '野外怪', '资源点', '敌方城池', '联盟建筑'],
  )
})

test('一支都没有时说清"这里本该有什么"，而不是画一个空列表', () => {
  const view = buildRallyPanel({ rallies: [], serverNow: NOW }, ME)
  assert.deepEqual(view.rows, [])
  assert.match(view.emptyText ?? '', /没有进行中的集结/)
  assert.match(view.emptyText ?? '', /加入小队或联盟/, '顺带说清发起的前提，玩家才知道下一步做什么')
})

test('列表还没拉回来时画一句说明，不画"没有集结"（那是两件不同的事）', () => {
  const view = buildRallyPanel(null, ME)
  assert.equal(view.noticeText, '集结列表还没拉回来，稍后再试')
  assert.equal(view.emptyText, null)
})

test('多支集结一次全出来，顺序按服务端给的来', () => {
  const view = buildRallyPanel({
    rallies: [rally({ rallyId: 'a' }), rally({ rallyId: 'b', scope: 'ALLIANCE' })],
    serverNow: NOW,
  }, ME)
  assert.deepEqual(view.rows.map((r) => r.rallyId), ['a', 'b'])
  assert.equal(view.rows[1]?.scopeText, '联盟集结')
  assert.equal(view.emptyText, null)
})
