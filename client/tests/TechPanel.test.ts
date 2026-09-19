/**
 * 职责：研究页读侧的纯逻辑用例（V03-a-S1，台账 #264）。
 * 依赖：node:test + game/tech/TechPanel（不碰 cc）。
 *
 * <p><b>四处最容易做假的地方</b>：① `canResearch`/`blockedReason` 只能照抄服务端，
 * 客户端不许自己再判一遍；② 倒计时/耗时只认服务端给的秒数；③ 没解锁的行也要画出来
 * （科技树的价值一半在"看得见前面有什么"）；④ 行的顺序原样照抄，不重排。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildTechPanel, costTextOf, effectTextOf, remainTextOf, schoolLabel, blockReasonText,
} from '../assets/scripts/game/tech/TechPanel'
import type { TechListView, TechView } from '../assets/scripts/net/generated/TechProtocol'

function tech(overrides: Partial<TechView> = {}): TechView {
  return {
    techId: 'tech_agri_wood', name: '屯田令', school: 'AGRICULTURE', effectAttr: 'WOOD_OUTPUT',
    effectValuePerLevelFixed: 400, level: 3, maxLevel: 30, requireAcademyLevel: 2,
    nextTimeSec: 5, nextCost: [{ type: 'WOOD', amount: 600 }, { type: 'STONE', amount: 200 }],
    researching: false, canResearch: true, blockedReason: 'NONE',
    ...overrides,
  }
}

function list(overrides: Partial<TechListView> = {}): TechListView {
  return {
    techs: [tech()],
    queue: { techId: null, finishAt: null, startedAt: 0, totalSeconds: 0, remainingSeconds: 0 },
    academyLevel: 2,
    serverNow: 1_700_000_000_000,
    ...overrides,
  }
}

test('列表没拉回来时画一句说明，不画一个假科技树', () => {
  const view = buildTechPanel(null)
  assert.equal(view.rows.length, 0)
  assert.match(view.noticeText ?? '', /还没拉回来/)
})

test('拉不到时把服务端给的理由原样放上说明行（与赛季/榜同一条纪律）', () => {
  const failed = buildTechPanel(null, '请求太频繁，稍后再试')
  assert.equal(failed.noticeText, '请求太频繁，稍后再试')
  const stale = buildTechPanel(list(), '服务繁忙')
  assert.equal(stale.noticeText, '服务繁忙')
  assert.equal(stale.rows.length, 1, '拉不到不等于列表没了：上一次那份留着')
})

test('一行把等级、效果、成本、耗时都摊开，效果是定点百分比不是小数', () => {
  const view = buildTechPanel(list())
  const row = view.rows[0]
  assert.equal(row?.name, '屯田令')
  assert.equal(row?.schoolText, '农政')
  assert.equal(row?.levelText, '3 / 30 级')
  assert.equal(row?.effectText, '木材产量 +4%/级', '400 定点 = 4%，不是 0.04')
  assert.equal(row?.costText, '木材 600 · 石料 200')
  assert.equal(row?.timeText, '5 分')
  assert.equal(view.academyText, '学院 2 级')
})

test('满级那行：不显示 +0%、不显示成本与耗时，原因写"已满级"', () => {
  const view = buildTechPanel(list({
    techs: [tech({ level: 30, canResearch: false, blockedReason: 'MAX_LEVEL', effectValuePerLevelFixed: null })],
  }))
  const row = view.rows[0]
  assert.equal(row?.effectText, null, '+0% 读起来像"升了也没用"')
  assert.equal(row?.costText, '已满级')
  assert.equal(row?.timeText, null)
  assert.equal(row?.reasonText, '已满级')
})

test('三条拒绝原因各有各的话，且不逐字照抄枚举名', () => {
  assert.equal(blockReasonText('ACADEMY_LOW'), '学院等级不足')
  assert.equal(blockReasonText('QUEUE_BUSY'), '研究队列被占用')
  assert.equal(blockReasonText('RESOURCE_LOW'), '资源不足')
  assert.equal(blockReasonText('NONE'), null, '能研究时不写一句"没问题"占地方')

  const view = buildTechPanel(list({
    techs: [tech({ canResearch: false, blockedReason: 'ACADEMY_LOW', requireAcademyLevel: 5 })],
  }))
  assert.equal(view.rows[0]?.canResearch, false)
  assert.equal(view.rows[0]?.reasonText, '学院等级不足')
  assert.equal(view.rows[0]?.costText, '木材 600 · 石料 200', '被拒的行照样把成本摊开：玩家要据此决定去攒什么')
})

test('队列行：有在研项时写清是哪一项、还剩多久；没有时空着', () => {
  const busy = buildTechPanel(list({
    queue: { techId: 'tech_agri_wood', finishAt: 1_700_000_010_000, startedAt: 1_700_000_000_000, totalSeconds: 7200, remainingSeconds: 7500 },
  }))
  assert.equal(busy.queueText, '正在研究 屯田令 · 2 小时 5 分')
  const idle = buildTechPanel(list())
  assert.equal(idle.queueText, null)
})

test('剩余时间只用服务端给的秒数：天/小时/分/秒四档，0 与负数都写"即将完成"', () => {
  assert.equal(remainTextOf(7500), '2 小时 5 分')
  assert.equal(remainTextOf(300), '5 分')
  assert.equal(remainTextOf(330), '5 分 30 秒')
  assert.equal(remainTextOf(45), '45 秒')
  assert.equal(remainTextOf(0), '即将完成')
  assert.equal(remainTextOf(-3), '即将完成', '不给负数')
})

test('没解锁的行也画出来：行数与服务端给的条数严格一致，顺序原样不重排', () => {
  const view = buildTechPanel(list({
    techs: [
      tech({ techId: 'b', name: '乙', school: 'MILITARY', canResearch: false, blockedReason: 'QUEUE_BUSY' }),
      tech({ techId: 'a', name: '甲', school: 'AGRICULTURE' }),
    ],
  }))
  assert.deepEqual(view.rows.map((r) => r.techId), ['b', 'a'], '顺序照抄服务端，不按学派或成本重排')
  assert.equal(view.rows.length, 2)
  assert.equal(view.rows[0]?.reasonText, '研究队列被占用')
})

test('成本与名字：0 量资源不显示，表里没有的名字退回原文而不是空行', () => {
  assert.equal(costTextOf([{ type: 'WOOD', amount: 0 }, { type: 'STONE', amount: 200 }]), '石料 200')
  assert.equal(costTextOf([]), '无需资源')
  assert.equal(schoolLabel('MYSTIC' as never), 'MYSTIC')
  assert.equal(effectTextOf(tech({ effectValuePerLevelFixed: 150 })), '木材产量 +1.5%/级')
})
