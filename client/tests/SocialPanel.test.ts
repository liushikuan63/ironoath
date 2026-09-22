/**
 * 职责：SocialPanel 的单测 —— B10 验收 1（小队不被稀释）、3（人数上限可解释）、
 * 6（一键帮助红点清零）、12（过期事件置灰）。
 * 依赖：node:test / node:assert。
 *
 * <p>重点盯四条：
 * <ol>
 *   <li><b>入盟后小队信息一个字段都不能少</b>（验收 1）。把小队页签藏进联盟是最省事的做法，
 *       也正是 B10 明令禁止的稀释</li>
 *   <li><b>人数上限必须能解释</b>（验收 3）。卡在 5 人而不说为什么，玩家会以为是 bug</li>
 *   <li><b>一键帮助的条数要跳过已帮过的</b>（验收 6）。否则 20 次额度全花在同一个人身上，
 *       玩家看到的是「我点了 20 次却只帮到 5 个人」</li>
 *   <li><b>过期事件原样透传 expired</b>（验收 12）。客户端不自己判断多久算过期 ——
 *       响应窗口是玩法数值（支援来得及来不及），不是显示规则</li>
 * </ol>
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildAllianceSection, buildEventRow, buildHelpRow, buildSocialPanel, buildSquadSection,
  canDo, capHint, channelText, eventTypeText, rallyText, speedupText,
} from '../assets/scripts/game/social/SocialPanel'
import type {
  AllianceMember, AllianceView, HelpRequestView, SocialEventView, SocialSummaryResp, SquadMember, SquadView,
} from '../assets/scripts/net/generated/SocialProtocol'

const MINUTE = 60_000

function squadMember(id: string, overrides: Partial<SquadMember> = {}): SquadMember {
  return {
    id, name: `玩家${id}`, power: 1200, lastActiveAt: 9_000, role: 'MEMBER', mainCityLevel: 6,
    ...overrides,
  }
}

function squad(overrides: Partial<SquadView> = {}): SquadView {
  return {
    id: 's1', name: '五个人', leaderId: 'leader',
    members: [squadMember('leader', { role: 'LEADER' }), squadMember('a'), squadMember('b')],
    level: 1, exp: 120, expToNext: 500, memberCap: 5, shopLevel: 0, squadCoin: 300,
    allianceId: null, isSubSquad: false,
    dailyQuestProgress: 7, dailyQuestTarget: 20,
    serverNow: 10_000,
    ...overrides,
  }
}

function allianceMember(id: string, overrides: Partial<AllianceMember> = {}): AllianceMember {
  return {
    id, name: `盟友${id}`, power: 5000, role: 'MEMBER', contribution: 60,
    lastActiveAt: 9_000, squadId: null, squadName: null,
    ...overrides,
  }
}

function alliance(overrides: Partial<AllianceView> = {}): AllianceView {
  return {
    id: 'a1', name: '铁誓同盟', tag: 'IRON', leaderId: 'leader',
    level: 1, exp: 0, memberCap: 30, memberCount: 3, fund: 2600,
    // 已研究的科技：协议里是必填（空数组=一项都没研究），夹具漏了会让 tsc 编译失败，
    // 而 tsc 一失败，node --test 那几百项就一条都不会执行
    techs: [],
    territoryCount: 0, territoryCap: 1, myRole: 'MEMBER', myContribution: 260,
    myDonateToday: 1,
    // 每档每日一次（2026-09-13 裁决）：已用档位与上限都由服务端下发，计数只是冗余展示值
    donateTiersUsed: [0], donateDailyCap: 3,
    announcement: '每晚八点集结', version: 7, serverNow: 10_000,
    ...overrides,
  }
}

function summary(overrides: Partial<SocialSummaryResp> = {}): SocialSummaryResp {
  return {
    squad: squad(),
    alliance: alliance(),
    nationId: null,
    pendingInvites: 0,
    pendingHelps: 2,
    helpRemainingToday: 20,
    events: [],
    serverNow: 10_000,
    ...overrides,
  }
}

function help(overrides: Partial<HelpRequestView> = {}): HelpRequestView {
  return {
    requestId: 'h1', fromPlayerId: 'a', fromPlayerName: '玩家a', kind: 'BUILDING',
    targetDesc: '伐木场 Lv7→8', remainingSeconds: 600, helpedCount: 2, alreadyHelped: false,
    ...overrides,
  }
}

function event(overrides: Partial<SocialEventView> = {}): SocialEventView {
  return {
    eventId: 'e1', type: 'MEMBER_ATTACKED', title: '盟友 张三 正在被攻击',
    body: null, coord: { x: 120, y: 88 }, relatedId: null,
    occurredAt: 9_000, expired: false,
    ...overrides,
  }
}

// ---------- 验收 1：小队不被稀释 ----------

test('验收1：入盟后小队区块必须同时给出「分队」与「小队」两个身份，且功能说明一个不少', () => {
  const independent = buildSquadSection(squad())
  assert.equal(independent.subSquadText, '独立小队（未加入联盟）')

  const sub = buildSquadSection(squad({ allianceId: 'a1', isSubSquad: true }))
  assert.equal(sub.joined, true)
  assert.match(sub.subSquadText ?? '', /分队/)
  assert.match(sub.subSquadText ?? '', /聊天 \/ 互助 \/ 集结功能全部保留/,
    '把小队页签藏进联盟就是稀释，B10 明令禁止')
  // 入盟前后，小队自己的数据一个字段都不能少
  assert.equal(sub.memberText, independent.memberText)
  assert.equal(sub.levelText, independent.levelText)
  assert.equal(sub.questText, independent.questText)
  assert.equal(sub.coinText, independent.coinText)
  assert.equal(sub.members.length, independent.members.length)
})

test('未加入小队时也要给完整区块（创建入口是招募的第一现场），并说明解锁门槛', () => {
  const section = buildSquadSection(null)
  assert.equal(section.joined, false)
  assert.equal(section.title, '未加入小队')
  assert.match(section.capHint ?? '', /主城 5 级/)
  assert.match(section.capHint ?? '', /开服第 1 天/)
  assert.deepEqual(section.members, [])
})

// ---------- 验收 3：人数上限可解释 ----------

test('验收3：卡在 5 人时必须说出下一档的门槛在队长主城等级上，而不是小队等级', () => {
  assert.equal(capHint(squad({ members: [squadMember('l'), squadMember('a'), squadMember('b'),
    squadMember('c'), squadMember('d')], memberCap: 5 })),
  '队长主城升到 8 级可扩到 8 人；小队 Lv3 可扩到 10 人')

  assert.equal(capHint(squad({ memberCap: 8, level: 2,
    members: Array.from({ length: 8 }, (_, i) => squadMember(`m${i}`)) })),
  '小队升到 Lv3 可扩到 10 人')
})

test('未满员或已到 10 人上限时不给提示（已经到顶还提示怎么扩到 10 人是噪音）', () => {
  assert.equal(capHint(squad()), null, '3/5 未满员')
  assert.equal(capHint(squad({ memberCap: 10, level: 3,
    members: Array.from({ length: 10 }, (_, i) => squadMember(`m${i}`)) })), null)
})

test('小队等级与活跃度的展示：满级时不再显示经验条', () => {
  assert.equal(buildSquadSection(squad({ level: 1, exp: 120, expToNext: 500 })).levelText,
    'Lv1 · 活跃度 120/500')
  assert.equal(buildSquadSection(squad({ level: 3, exp: 0, expToNext: 0 })).levelText, 'Lv3 · 已满级')
})

// ---------- 联盟区块 ----------

test('联盟区块把资金、贡献值、领地、职位与今日捐献档数都摆出来', () => {
  const members = [allianceMember('leader', { role: 'LEADER' }),
    allianceMember('a', { squadId: 's1', squadName: '铁砧前哨' })]
  const section = buildAllianceSection(alliance(), members)
  assert.equal(section.joined, true)
  assert.equal(section.title, '[IRON] 铁誓同盟')
  assert.equal(section.memberText, '3/30 人')
  assert.equal(section.fundText, '联盟资金 2600')
  assert.equal(section.contributionText, '我的贡献值 260')
  assert.equal(section.territoryText, '领地 0/1')
  assert.equal(section.myRoleText, '成员')
  assert.equal(section.donateText, '今日捐献 1/3 档')
  assert.equal(section.members.length, 2)
  assert.equal(section.members[1]?.squadText, '分队 铁砧前哨', '盟主集结时要能按分队点名')
  // 这一条上一版写的是 `'分队 s1'` —— 绿灯把缺陷钉成了规格（台账 #422）：`squadId` 是内部标识，
  // 玩家读不懂，能印的只有服务端下发的名字。判据反过来钉：名字要在、id 一个字都不许出现
  assert.ok(!String(section.members[1]?.squadText).includes('s1'), '分队那一段不得印小队 id')
})

test('摆出来的是「今天还没捐过的档」：已用档位由服务端下发，客户端不再拿计数猜', () => {
  // 旧形态是"只要有额度就把三档全摆出来，点了才知道捐过"（协议只给计数）。
  // 2026-09-13 裁决每档每日一次之后，服务端下发 donateTiersUsed，这条才有可断言的东西
  assert.deepEqual(
    buildAllianceSection(alliance({ myDonateToday: 0, donateTiersUsed: [] }), []).donateTiersAvailable,
    [0, 1, 2])
  assert.deepEqual(
    buildAllianceSection(alliance({ myDonateToday: 2, donateTiersUsed: [0, 2] }), []).donateTiersAvailable,
    [1], '捐过 0 与 2 之后只剩 1 可点：摆错一个就是让玩家点一下收一次报错')
  assert.deepEqual(
    buildAllianceSection(alliance({ myDonateToday: 3, donateTiersUsed: [0, 1, 2] }), [])
      .donateTiersAvailable, [])
})

test('今日档数上限取自服务端（客户端不写死 3，改 alliance_config 不该让面板撒谎）', () => {
  assert.equal(
    buildAllianceSection(alliance({ myDonateToday: 1, donateTiersUsed: [1], donateDailyCap: 2 }), [])
      .donateText,
    '今日捐献 1/2 档')
})

test('人数已满时给出扩容提示（这是中后期最大的资金消耗点，玩家要看得见它）', () => {
  assert.equal(buildAllianceSection(alliance({ memberCount: 30, memberCap: 30 }), []).expandText,
    '人数已满：盟主可用联盟资金扩容（这是中后期最大的资金消耗点）')
  assert.equal(buildAllianceSection(alliance(), []).expandText, null)
})

test('未加入联盟时给完整空区块，不返回 null', () => {
  const section = buildAllianceSection(null, [])
  assert.equal(section.joined, false)
  assert.equal(section.title, '未加入联盟')
  assert.deepEqual(section.donateTiersAvailable, [])
})

test('三天没上线的成员标记为不活跃：挂名不上线的人提供不了庇护（B10 关键设计点 2）', () => {
  const members = [
    allianceMember('active', { lastActiveAt: 10_000 }),
    allianceMember('gone', { lastActiveAt: 10_000 - 4 * 24 * 3600 * 1000 }),
  ]
  const section = buildAllianceSection(alliance({ serverNow: 10_000 }), members)
  assert.equal(section.members[0]?.inactive, false)
  assert.equal(section.members[1]?.inactive, true)
  assert.equal(section.members[1]?.activeText, '4 天前')
  assert.equal(section.members[0]?.activeText, '刚刚')
})

// ---------- 验收 6：一键帮助 ----------

test('验收6：一键帮助的条数 = min(未帮过的条数, 今日剩余额度)', () => {
  const helps = [help({ requestId: 'h1' }), help({ requestId: 'h2', alreadyHelped: true }),
    help({ requestId: 'h3' })]
  const panel = buildSocialPanel(summary(), helps, [], 0, 10_000)
  assert.equal(panel.helpRows.length, 3)
  assert.equal(panel.helpAllCount, 2, '已帮过的必须跳过，否则 20 次额度会全花在同一个人身上')

  const limited = buildSocialPanel(summary({ helpRemainingToday: 1 }), helps, [], 0, 10_000)
  assert.equal(limited.helpAllCount, 1, '额度只剩 1 次时不能承诺帮 2 条')

  const none = buildSocialPanel(summary({ helpRemainingToday: 0 }), helps, [], 0, 10_000)
  assert.equal(none.helpAllCount, 0)
})

test('帮助倒计时随「响应到现在」推进，且绝不为负', () => {
  assert.equal(buildHelpRow(help({ remainingSeconds: 600 }), 0).countdownText, '10分00秒')
  assert.equal(buildHelpRow(help({ remainingSeconds: 600 }), 60_000).countdownText, '09分00秒')
  assert.equal(buildHelpRow(help({ remainingSeconds: 600 }), 999_000).countdownText, '已完成')
  assert.equal(buildHelpRow(help({ remainingSeconds: 0 }), 0).countdownText, '已完成')
})

test('红点：邀请 / 互助 / 未读事件三个来源，任一非零就亮', () => {
  assert.equal(buildSocialPanel(summary(), [], [], 0, 0).redDots.any, true, 'pendingHelps=2')
  assert.equal(buildSocialPanel(summary({ pendingHelps: 0 }), [], [], 0, 0).redDots.any, false)
  assert.equal(buildSocialPanel(summary({ pendingHelps: 0, pendingInvites: 1 }), [], [], 0, 0).redDots.any, true)
  const withEvents = buildSocialPanel(summary({ pendingHelps: 0 }), [], [], 0, 0)
  assert.equal(withEvents.redDots.unreadEvents, 0)
})

// ---------- 验收 12：过期事件 ----------

test('验收12：expired 原样透传，客户端不自己判断多久算过期', () => {
  assert.equal(buildEventRow(event(), 0, 10_000).expired, false)
  assert.equal(buildEventRow(event({ expired: true }), 0, 10_000).expired, true)
  assert.equal(buildEventRow(event({ coord: null }), 0, 10_000).coordText, null)
  assert.equal(buildEventRow(event(), 0, 10_000).coordText, '(120, 88)')
  assert.equal(buildEventRow(event({ occurredAt: 10_000 - 3 * 3600 * 1000 }), 0, 10_000).timeText, '3 小时前')
})

test('事件类型标签：验收 2 的「小队已解散」通知必须有自己的文案', () => {
  assert.equal(eventTypeText('SQUAD_DISBANDED'), '小队已解散')
  assert.equal(eventTypeText('MEMBER_ATTACKED'), '盟友被攻击')
  assert.equal(eventTypeText('RALLY_DEPARTED'), '集结已出发')
  assert.equal(eventTypeText('SOMETHING_NEW'), 'SOMETHING_NEW', '不认识的原样显示，不静默变空白')
})

test('验收5 的被攻击事件在汇总里作为未读事件出现', () => {
  const panel = buildSocialPanel(summary({ pendingHelps: 0, events: [event()] }), [], [], 0, 10_000)
  assert.equal(panel.events.length, 1)
  assert.equal(panel.redDots.unreadEvents, 1)
  assert.equal(panel.redDots.any, true)
})

// ---------- 其余展示 ----------

test('集结文本带人数、目标、已凑兵力与统一出发倒计时（验收 11）', () => {
  const rally = {
    rallyId: 'r1', scope: 'ALLIANCE' as const, groupId: 'a1', initiatorId: 'leader',
    targetCoord: { x: 200, y: 160 }, targetType: 'MONSTER' as const,
    maxMembers: 20, joinedCount: 5, totalTroops: 4200,
    prepareUntil: 10_000 + 5 * MINUTE, departAt: 10_000 + 5 * MINUTE,
    status: 'PREPARING' as const, members: [], heroSlots: [], serverNow: 10_000,
  }
  const text = rallyText(rally, 0, 10_000)
  assert.match(text, /联盟集结 · 5\/20 人/)
  assert.match(text, /目标 \(200, 160\)/)
  assert.match(text, /已凑兵力 4200/)
  assert.match(text, /05分00秒后统一出发/)

  const departed = rallyText({ ...rally, status: 'DEPARTED' as const }, 0, 10_000)
  assert.match(departed, /已出发/)
  assert.doesNotMatch(departed, /统一出发/)
})

test('权限查表：客户端只做 includes，不自己推导谁能做什么（验收 4）', () => {
  assert.equal(canDo(['KICK_MEMBER', 'START_RALLY'], 'KICK_MEMBER'), true)
  assert.equal(canDo(['START_RALLY'], 'KICK_MEMBER'), false)
  assert.equal(canDo([], 'DONATE'), false)
})

test('频道页签名与加速比例文本', () => {
  assert.equal(channelText('WORLD'), '世界')
  assert.equal(channelText('ALLIANCE'), '联盟')
  assert.equal(channelText('SQUAD'), '小队')
  assert.equal(channelText('PRIVATE'), '私聊')
  assert.equal(speedupText(2000), '20%')
  assert.equal(speedupText(5000), '50%')
})

test('空汇总也能组装（刚建号、什么都没加入）', () => {
  const panel = buildSocialPanel(summary({ squad: null, alliance: null, pendingHelps: 0 }), [], [], 0, 0)
  assert.equal(panel.squad.joined, false)
  assert.equal(panel.alliance.joined, false)
  assert.equal(panel.nationText, null)
  assert.equal(panel.redDots.any, false)
  assert.deepEqual(panel.helpRows, [])
  assert.deepEqual(panel.events, [])
})

test('国家 id 下发时才显示国家区块（B13 接入前恒为 null）', () => {
  assert.equal(buildSocialPanel(summary({ nationId: 'n1' }), [], [], 0, 0).nationText, '国家 n1')
})
