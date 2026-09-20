/**
 * 职责：编成弹层三态与集结参数的算术用例（B26 S13b）。
 * 依赖：node（`node --test`）。
 *
 * <p>盯三件事：① 界与默认值只能来自读口（客户端一个数都不自己挑）；
 * ② 越界是夹不是拒；③ 读口没到时不许出现"猜出来的默认值"。
 * 这三条错了的表现分别是"滑条上限必然被服务端夹掉"、"点一下没反应"、
 * 和"面板显示 20 人而实际只允许 5 人"。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  blockedReason, defaultParams, kindSubmitLabel, kindTitle, kindToggleLabel,
  nextKind, policyFor, rebind, stepControl, stepFor,
} from '../assets/scripts/game/social/RallyCompose'
import type { RallyPolicyResp, RallyPolicyView } from '../assets/scripts/net/generated/SocialProtocol'

function policy(over: Partial<RallyPolicyView> = {}): RallyPolicyView {
  return {
    minMembers: 2, maxMembers: 5,
    minPrepareMinutes: 5, maxPrepareMinutes: 60,
    defaultPrepareMinutes: 60, canStart: true, reason: null,
    ...over,
  }
}

function resp(squad: RallyPolicyView, alliance: RallyPolicyView): RallyPolicyResp {
  return { squad, alliance, serverNow: 0 }
}

test('三态循环走完回到出征，被挡住的那一档也在循环里', () => {
  assert.equal(nextKind('MARCH'), 'SQUAD_RALLY')
  assert.equal(nextKind('SQUAD_RALLY'), 'ALLIANCE_RALLY')
  assert.equal(nextKind('ALLIANCE_RALLY'), 'MARCH')
})

test('每一档的确认键与切换键都写明它切去哪：点之前就知道', () => {
  assert.deepEqual(
    [kindTitle('MARCH'), kindSubmitLabel('MARCH'), kindToggleLabel('MARCH')],
    ['出征', '出征', '改成小队集结'],
  )
  assert.equal(kindSubmitLabel('SQUAD_RALLY'), '发起小队集结')
  assert.equal(kindToggleLabel('SQUAD_RALLY'), '改成联盟集结')
  assert.equal(kindSubmitLabel('ALLIANCE_RALLY'), '发起联盟集结')
  assert.equal(kindToggleLabel('ALLIANCE_RALLY'), '改回出征')
  assert.equal(kindTitle('ALLIANCE_RALLY'), '集结', '两档集结共用一个标题前缀')
})

test('MARCH 没有政策，读口没到也没有：拿不到界就不该出现猜出来的默认值', () => {
  assert.equal(policyFor('MARCH', resp(policy(), policy())), null)
  assert.equal(policyFor('SQUAD_RALLY', null), null)
  assert.equal(defaultParams('SQUAD_RALLY', null), null)
  assert.equal(defaultParams('ALLIANCE_RALLY', null), null)
})

test('小队档没有参数行：SquadRallyReq 不吃人数与时长，画两个加减号就是一组点了什么都不发生的控件', () => {
  const r = resp(policy({ maxMembers: 5 }), policy({ maxMembers: 20 }))
  assert.equal(defaultParams('SQUAD_RALLY', r), null)
  assert.equal(defaultParams('MARCH', r), null)
  assert.equal(defaultParams('ALLIANCE_RALLY', r)?.members.max, 20)
  // 但"能不能发起"这一档仍然要说得清 —— 参数与门是两件事
  assert.equal(policyFor('SQUAD_RALLY', r)?.maxMembers, 5, '政策本身读得到，只是不变成控件')
})

test('读口给的默认时长越界时夹进界内：服务端夹取口径与写口一致，面板不许显示一个必然被夹掉的数', () => {
  const p = defaultParams('ALLIANCE_RALLY', resp(policy(), policy({
    minPrepareMinutes: 10, maxPrepareMinutes: 30, defaultPrepareMinutes: 999,
  })))
  assert.equal(p?.prepare.value, 30)
})

test('组织掉到最低人数以下时上界不会低于下界：maxMembers 取的是实际人数，可以小于 minMembers', () => {
  const p = defaultParams('ALLIANCE_RALLY', resp(policy(), policy({ minMembers: 3, maxMembers: 2 })))
  assert.equal(p?.members.min, 3)
  assert.equal(p?.members.max, 3, '夹成 3 而不是留一个 2<3 的空区间')
  assert.equal(p?.members.value, 3)
})

test('默认值一律来自读口：人数用它此刻能设的上限，时长用它给的默认档', () => {
  const p = defaultParams('ALLIANCE_RALLY', resp(policy(), policy({
    minMembers: 3, maxMembers: 20, minPrepareMinutes: 5, maxPrepareMinutes: 120,
    defaultPrepareMinutes: 120,
  })))
  assert.equal(p?.members.value, 20)
  assert.equal(p?.prepare.value, 120)
  assert.equal(p?.members.min, 3)
  assert.equal(p?.prepare.min, 5)
})

test('步长由界推出：跨度 1 走一步，跨度大时十下点完，且永不为 0', () => {
  assert.equal(stepFor(5, 5), 1)
  assert.equal(stepFor(5, 6), 1)
  assert.equal(stepFor(5, 15), 1)
  assert.equal(stepFor(0, 120), 12)
  assert.equal(stepFor(0, 119), 12)
})

test('点 ＋/− 越界是夹住而不是拒绝，也不是绕回另一端', () => {
  const base = defaultParams('ALLIANCE_RALLY', resp(policy(), policy({
    minMembers: 3, maxMembers: 20, minPrepareMinutes: 5, maxPrepareMinutes: 60,
    defaultPrepareMinutes: 30,
  })))!
  assert.equal(base.prepare.step, 6, '跨度 55 ⇒ 每下一步走 6 分钟')
  assert.equal(stepControl(base.members, 1).value, 20, '默认就在上限，再加不动')
  assert.equal(stepControl(base.prepare, -1).value, 24)
  assert.equal(stepControl(base.prepare, -10).value, 5, '一路减到底')
  assert.equal(stepControl(base.prepare, 10).value, 60)
})

test('不能发起时把服务端那句原因原样交出去；能发起与"还不知道"都是 null', () => {
  const blocked = resp(policy(), policy({ canStart: false, reason: '你在联盟里还不是干部，发起不了集结' }))
  assert.equal(blockedReason('ALLIANCE_RALLY', blocked), '你在联盟里还不是干部，发起不了集结')
  assert.equal(blockedReason('SQUAD_RALLY', blocked), null, '另一档没被挡就不该跟着灰')
  assert.equal(blockedReason('ALLIANCE_RALLY', null), null, '读口没到是"暂时不知道"，不是"你不行"')
  assert.equal(blockedReason('MARCH', blocked), null)
})

test('读口刷新后保留玩家调过的值，只把越界部分夹回新界内', () => {
  const before = defaultParams('ALLIANCE_RALLY', resp(policy(), policy({
    minMembers: 3, maxMembers: 20, minPrepareMinutes: 5, maxPrepareMinutes: 120,
    defaultPrepareMinutes: 120,
  })))!
  const tuned = { members: stepControl(before.members, -1), prepare: before.prepare }
  assert.equal(tuned.members.value, 18)

  // 联盟掉人了：上限从 20 变成 10
  const after = rebind(tuned, 'ALLIANCE_RALLY', resp(policy(), policy({
    minMembers: 3, maxMembers: 10, minPrepareMinutes: 5, maxPrepareMinutes: 120,
    defaultPrepareMinutes: 120,
  })))!
  assert.equal(after.members.value, 10, '越界的部分夹回新上限')
  assert.equal(after.members.max, 10, '界本身跟着读口走')
  assert.equal(after.prepare.value, 120, '没越界的值原样保留，不重置回默认')
})

test('换档重新按新档的界取默认值：小队那一档没有参数行，切到联盟才长出两格', () => {
  const r = resp(policy({ minMembers: 2, maxMembers: 5 }),
    policy({ minMembers: 3, maxMembers: 20, defaultPrepareMinutes: 45 }))
  assert.equal(defaultParams('SQUAD_RALLY', r), null)
  const alliance = defaultParams('ALLIANCE_RALLY', r)!
  assert.equal(alliance.members.value, 20)
  assert.equal(alliance.prepare.value, 45)
})
