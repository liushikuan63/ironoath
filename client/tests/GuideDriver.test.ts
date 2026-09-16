/**
 * 职责：引导驱动器的用例（B18 验收 2/6/8 的逻辑面 + 埋点三条动作）。
 * 依赖：node:test + game/guide/GuideDriver（纯逻辑，不碰 cc）。
 *
 * <p><b>这几条用例盯的是四件事</b>：① 客户端**不自己推进**（位置只跟着服务端的 nextStepIndex 走）；
 * ② 客户端**不自己判该不该看**（applies/nextStepIndex 决定，不按等级猜）；
 * ③ 一步画什么、能不能跳，全部照搬下发的那一行（验收 1 的"没有硬编码"由此成立）；
 * ④ 埋点三个动作各有各的时机，而"乱点"每步只记一次。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { GuideDriver, parseGuideMask } from '../assets/scripts/game/guide/GuideDriver'
import type { GuideScriptResp, GuideStepView } from '../assets/scripts/net/generated/GuideProtocol'

const NOW = 1_800_000_000_000

function step(overrides: Partial<GuideStepView> & { id: string, stepIndex: number }): GuideStepView {
  return {
    name: '步骤' + overrides.stepIndex,
    trigger: 'PANEL_OPEN',
    panelKey: 'city',
    highlightPath: 'city',
    maskArea: 'full',
    text: '服务端下发的文案 ' + overrides.stepIndex,
    skippable: false,
    ...overrides,
  }
}

function script(steps: GuideStepView[], overrides: Partial<GuideScriptResp> = {}): GuideScriptResp {
  return { steps, version: '1', nextStepIndex: 1, applies: true, serverNow: NOW, ...overrides }
}

/** 收下的埋点，按调用顺序排。 */
function tracker(): { calls: string[]; track: (a: string, id: string) => void } {
  const calls: string[] = []
  return {
    calls,
    track: (action, stepId) => calls.push(`${action}:${stepId}`),
  }
}

test('该不该看引导是服务端说的：applies=false、已结束、空脚本三种都起不了驱动器', () => {
  const one = [step({ id: 'g1', stepIndex: 1 })]
  assert.equal(GuideDriver.from(script(one, { applies: false })), null)
  assert.equal(GuideDriver.from(script(one, { nextStepIndex: null })), null)
  assert.equal(GuideDriver.from(script([], { nextStepIndex: 1 })), null)
  assert.notEqual(GuideDriver.from(script(one)), null)
})

test('PANEL_OPEN 只在对应面板弹，STATE_REACHED 不等面板', () => {
  const driver = GuideDriver.from(script([
    step({ id: 'g1', stepIndex: 1, panelKey: 'city' }),
    step({ id: 'g7', stepIndex: 2, trigger: 'STATE_REACHED', panelKey: 'social' }),
  ]))
  assert.notEqual(driver, null)
  const guide = driver as GuideDriver

  assert.equal(guide.frameFor('quest'), null, '在任务面板上不该弹"升级主城"')
  assert.equal(guide.frameFor('city')?.step.id, 'g1')

  guide.applyProgress(2)
  assert.equal(guide.frameFor('world')?.step.id, 'g7', '解锁类的那一步在别的面板也要弹')
})

test('「第 N/M 步」用下发的序号与步数，不在客户端数（两处排序迟早分叉）', () => {
  const guide = GuideDriver.from(script([
    step({ id: 'g1', stepIndex: 1 }),
    step({ id: 'g2', stepIndex: 2 }),
    step({ id: 'g3', stepIndex: 3 }),
  ], { nextStepIndex: 3 })) as GuideDriver
  const frame = guide.frameFor('city')
  assert.deepEqual(frame?.position, { index: 3, total: 3 })
  assert.equal(frame?.step.text, '服务端下发的文案 3', '文案一个字都不许客户端补')
})

test('跳过按钮只在 skippable=true 出现；对强制步请求跳过拿不到东西', () => {
  const forced = GuideDriver.from(script([step({ id: 'g1', stepIndex: 1, skippable: false })])) as GuideDriver
  assert.equal(forced.frameFor('city')?.showSkip, false)
  const t1 = tracker()
  assert.equal(forced.skipAction(t1.track), null)
  assert.deepEqual(t1.calls, [], '被拒的请求连埋点都不该记')

  const optional = GuideDriver.from(script([step({ id: 'g1', stepIndex: 1, skippable: true })])) as GuideDriver
  const t2 = tracker()
  assert.equal(optional.frameFor('city')?.showSkip, true)
  assert.deepEqual(optional.skipAction(t2.track), { stepId: 'g1' })
  assert.deepEqual(t2.calls, ['skip:g1'])
})

test('验收 6：遮罩几何按表解析，写坏一律退回整屏（半生效比不生效更糟）', () => {
  assert.deepEqual(parseGuideMask('full'), { full: true, rect: null })
  assert.deepEqual(parseGuideMask('10,20,300,400'), { full: false, rect: [10, 20, 300, 400] })
  for (const broken of ['10,20,300', '10,20,300,400,500', 'a,b,c,d', '10,-1,300,400', '']) {
    assert.deepEqual(parseGuideMask(broken), { full: true, rect: null }, `坏值 "${broken}" 必须退回整屏`)
  }
})

test('位置只跟着服务端走：advanced=false 留在原步，null 表示结束', () => {
  const guide = GuideDriver.from(script([
    step({ id: 'g1', stepIndex: 1 }), step({ id: 'g2', stepIndex: 2 }),
  ])) as GuideDriver

  guide.applyProgress(1)
  assert.equal(guide.current()?.id, 'g1', '服务端没认这一步，界面就不许往前走')
  assert.equal(guide.finished, false)

  guide.applyProgress(2)
  assert.equal(guide.current()?.id, 'g2')

  guide.applyProgress(null)
  assert.equal(guide.finished, true)
  assert.equal(guide.frameFor('city'), null)
})

test('enter 每步只记一次（切面板重画不该再算一次进入），complete 带步骤 id', () => {
  const guide = GuideDriver.from(script([
    step({ id: 'g1', stepIndex: 1 }), step({ id: 'g2', stepIndex: 2 }),
  ])) as GuideDriver
  const t = tracker()

  guide.markShown(t.track)
  guide.frameFor('city')
  guide.markShown(t.track)
  assert.deepEqual(t.calls, ['enter:g1'])

  guide.completedAction(t.track)
  assert.deepEqual(t.calls, ['enter:g1', 'complete:g1'])

  guide.applyProgress(2)
  guide.markShown(t.track)
  assert.deepEqual(t.calls.slice(-1), ['enter:g2'], '换步之后该重新记一次进入')
})

test('乱点（高亮外）每步最多记一次，换步之后重新允许一次', () => {
  const guide = GuideDriver.from(script([
    step({ id: 'g1', stepIndex: 1 }), step({ id: 'g2', stepIndex: 2 }),
  ])) as GuideDriver
  const t = tracker()

  guide.outsideTap(t.track)
  guide.outsideTap(t.track)
  guide.outsideTap(t.track)
  assert.deepEqual(t.calls, ['outside_tap:g1'], '按次数记等于给刷埋点开一个口子')

  guide.applyProgress(2)
  guide.outsideTap(t.track)
  assert.deepEqual(t.calls, ['outside_tap:g1', 'outside_tap:g2'])
})

test('脚本版本随埋点走，看板才能把"改了脚本"与"玩家不做了"分开', () => {
  const guide = GuideDriver.from(script([step({ id: 'g1', stepIndex: 1 })], { version: '7' })) as GuideDriver
  assert.equal(guide.scriptVersion, '7')
})
