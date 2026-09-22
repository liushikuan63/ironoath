/**
 * 职责：社交面板的展示数据组装（B10 §1~§5，验收 1/3/6/7/12）。
 * 依赖：core/Countdown、core/FixedPoint、生成的协议类型（引擎无关，可脱离 Cocos 跑单测）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：能不能踢人由服务端下发的权限列表决定，
 * 人数上限由服务端算好下发，红点数由服务端给出。这里只做「协议 → 展示文本」的搬运。
 *
 * <p><b>四条纪律</b>，都来自 B10 的明文要求：
 * <ol>
 *   <li><b>小队不被稀释</b>（关键设计点 1、验收 1）：isSubSquad=true 时，
 *       面板必须同时画出「分队」与「小队」两个身份，聊天/互助/集结入口一个都不能少。
 *       把小队页签藏起来就是稀释</li>
 *   <li><b>人数上限要能解释</b>（验收 3）：卡在 5 人时必须说出为什么 ——
 *       「队长主城 8 级可扩到 8 人」。一个不说明原因的上限会被当成 bug</li>
 *   <li><b>一键帮助要跳过已帮过的</b>（验收 6）：否则玩家点一次「帮助全部」，
 *       20 次额度全花在同一个人身上，而他看到的是「我点了 20 次却只帮到 5 个人」</li>
 *   <li><b>过期事件置灰</b>（验收 12）：三小时前的「盟友被攻击」已经支援不上了，
 *       点进去只会看到一片废墟。与 B07 侦查情报同一条纪律</li>
 * </ol>
 */

import { formatCountdown } from '../../core/Countdown'
import * as FixedPoint from '../../core/FixedPoint'
import type {
  AllianceMember, AllianceRole, AllianceTechView, AllianceView, ChatChannel, ChatMessageView,
  HelpRequestView,
  RallyScope, RallyStatus, RallyView, SocialEventView, SocialEventType, SocialSummaryResp, SquadView,
} from '../../net/generated/SocialProtocol'

/** 小队区块。 */
export interface SquadSection {
  readonly joined: boolean
  readonly title: string
  /** 「3/5 人」 */
  readonly memberText: string
  readonly levelText: string
  /** 人数上限为什么是现在这个数（验收 3）。已经到顶时为 null */
  readonly capHint: string | null
  /** 双重身份提示：既是联盟分队又是小队（验收 1） */
  readonly subSquadText: string | null
  readonly questText: string
  readonly coinText: string
  readonly members: readonly SocialMemberRow[]
}

/** 联盟区块。 */
export interface AllianceSection {
  readonly joined: boolean
  readonly title: string
  readonly memberText: string
  readonly fundText: string
  readonly contributionText: string
  readonly levelText: string
  readonly territoryText: string
  /** 扩容提示；已是最高档为 null */
  readonly expandText: string | null
  /** 今日捐献：「今日捐献 1/3 档」 */
  readonly donateText: string
  readonly donateTiersAvailable: readonly number[]
  readonly myRoleText: string
  /** 联盟科技目录（B26 S9）：服务端那份原样转手，行怎么画由 AllianceTechCatalog 判 */
  readonly techs: readonly AllianceTechView[]
  readonly members: readonly SocialMemberRow[]
}

/** 一名成员（小队与联盟共用一行结构）。 */
export interface SocialMemberRow {
  readonly id: string
  readonly name: string
  readonly roleText: string
  /** 原职位枚举值（B26 S11）：弹层要标出「现任」，行标签继续用上面那份中文 */
  readonly role: string
  readonly powerText: string
  /** 最近活跃时间文本。不活跃的成员要能被一眼看出来（盟主据此决定补人） */
  readonly activeText: string
  readonly inactive: boolean
  readonly squadText: string | null
  readonly contributionText: string | null
}

/** 一条待帮助请求。 */
export interface HelpRow {
  readonly requestId: string
  readonly title: string
  readonly detail: string
  readonly countdownText: string
  /** 我已经帮过了 ⇒ 一键帮助会跳过它，按钮也要置灰 */
  readonly alreadyHelped: boolean
}

/** 一条社交事件。 */
export interface EventRow {
  readonly eventId: string
  readonly title: string
  readonly body: string | null
  readonly timeText: string
  readonly expired: boolean
  /** 可以跳转的坐标；没有为 null */
  readonly coordText: string | null
  readonly type: SocialEventType
}

/** 红点汇总。 */
export interface RedDots {
  readonly invites: number
  readonly helps: number
  readonly unreadEvents: number
  readonly any: boolean
}

/** 整个社交面板。 */
export interface SocialPanelView {
  readonly squad: SquadSection
  readonly alliance: AllianceSection
  readonly nationText: string | null
  readonly redDots: RedDots
  readonly helpRows: readonly HelpRow[]
  readonly helpRemainingText: string
  /** 一键帮助实际会帮到几条（跳过已帮过的）。为 0 时按钮应当置灰 */
  readonly helpAllCount: number
  readonly events: readonly EventRow[]
}

/** 判定「多久没上线算不活跃」。这是显示规则，不是游戏数值。 */
const INACTIVE_MILLIS = 3 * 24 * 3600 * 1000

/** 组装整个社交面板。 */
export function buildSocialPanel(resp: SocialSummaryResp, helpRequests: readonly HelpRequestView[],
                                 allianceMembers: readonly AllianceMember[],
                                 offsetMs: number, localNow: number): SocialPanelView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  // 响应发出到现在过去了多久。服务端的 remainingSeconds 是响应那一刻的快照，
  // 要让倒计时继续走就得减掉这段时间 —— 用 TimeSync 的偏移换算，不直接拿本地时钟比
  const elapsedMs = Math.max(0, localNow + offsetMs - resp.serverNow)
  const helpRows = helpRequests.map((request: HelpRequestView): HelpRow => buildHelpRow(request, elapsedMs))
  let helpAll = 0
  for (const row of helpRows) {
    if (!row.alreadyHelped) {
      helpAll++
    }
  }
  const events = resp.events.map((event: SocialEventView): EventRow => buildEventRow(event, offsetMs, localNow))
  return {
    squad: buildSquadSection(resp.squad),
    alliance: buildAllianceSection(resp.alliance, allianceMembers),
    nationText: resp.nationId === null ? null : `国家 ${resp.nationId}`,
    redDots: {
      invites: resp.pendingInvites,
      helps: resp.pendingHelps,
      unreadEvents: events.length,
      any: resp.pendingInvites > 0 || resp.pendingHelps > 0 || events.length > 0,
    },
    helpRows,
    helpRemainingText: `今日还可帮助 ${resp.helpRemainingToday} 次`,
    helpAllCount: Math.min(helpAll, resp.helpRemainingToday),
    events,
  }
}

/**
 * 小队区块。
 *
 * <p>未加入时也要给一个完整的区块（joined=false），而不是 null ——
 * 面板需要显示「你还没有小队」与创建入口，那是招募的第一现场。
 */
export function buildSquadSection(squad: SquadView | null): SquadSection {
  if (squad === null || squad === undefined) {
    return {
      joined: false,
      title: '未加入小队',
      memberText: '',
      levelText: '',
      capHint: '主城 5 级且开服第 1 天起可创建小队（5 人）',
      subSquadText: null,
      questText: '',
      coinText: '',
      members: [],
    }
  }
  return {
    joined: true,
    title: squad.name,
    memberText: `${squad.members.length}/${squad.memberCap} 人`,
    levelText: `Lv${squad.level}${squad.expToNext > 0 ? ` · 活跃度 ${squad.exp}/${squad.expToNext}` : ' · 已满级'}`,
    capHint: capHint(squad),
    // 验收 1：分队身份是「多一个归属」，不是「被联盟吞掉」。
    // 文案刻意把两个身份并列写出来，并且小队自己的功能入口一个都不减
    subSquadText: squad.isSubSquad
      ? `本小队是联盟分队，聊天 / 互助 / 集结功能全部保留`
      : '独立小队（未加入联盟）',
    questText: `今日小队任务 ${squad.dailyQuestProgress}/${squad.dailyQuestTarget} 只`,
    coinText: `小队币 ${squad.squadCoin}`,
    members: squad.members.map((member): SocialMemberRow => ({
      id: member.id,
      name: member.name,
      roleText: squadRoleText(member.role),
      role: String(member.role),
      powerText: `战力 ${member.power}`,
      activeText: activeText(member.lastActiveAt, squad.serverNow),
      inactive: squad.serverNow - member.lastActiveAt > INACTIVE_MILLIS,
      squadText: null,
      contributionText: null,
    })),
  }
}

/**
 * 人数上限为什么是现在这个数（验收 3）。
 *
 * <p><b>到顶时返回 null</b>：已经 10 人了还提示「怎么扩到 10 人」是噪音。
 * 没到顶时必须说清门槛 —— 第二档卡在「队长主城 8 级」而不是小队等级，
 * 这一点玩家自己推不出来，不说明他就会反复升级小队然后发现人数没变。
 */
export function capHint(squad: SquadView): string | null {
  if (squad.members.length < squad.memberCap) {
    return null
  }
  if (squad.memberCap >= 10) {
    return null
  }
  return squad.memberCap === 5
    ? '队长主城升到 8 级可扩到 8 人；小队 Lv3 可扩到 10 人'
    : '小队升到 Lv3 可扩到 10 人'
}

/**
 * 联盟区块。
 *
 * <p><b>成员列表不在 AllianceView 里，必须单独传进来</b>（验收 10）：
 * 联盟数据不能每帧全量同步，而 150 人的成员列表是其中最大的一块。
 * 所以汇总接口只给联盟本身的标量（资金、等级、人数、我的职位），
 * 成员列表由 POST /alliance/sync 按版本号下发 diff，缓存在客户端。
 */
export function buildAllianceSection(alliance: AllianceView | null,
                                     members: readonly AllianceMember[]): AllianceSection {
  if (alliance === null || alliance === undefined) {
    return {
      joined: false,
      title: '未加入联盟',
      memberText: '',
      fundText: '',
      contributionText: '',
      levelText: '',
      territoryText: '',
      expandText: null,
      donateText: '',
      donateTiersAvailable: [],
      myRoleText: '',
      techs: [],
      members: [],
    }
  }
  // 档数上限与「今天已经捐过哪几档」都由服务端下发（2026-09-13 裁决：每档每日一次，
  // 收口清单 §三·补 A1）。客户端不再写死 3（那是 global/alliance_config 的数字），
  // 也不再拿一个计数去猜该摆哪个按钮 —— 那份猜测的代价是玩家点一下才收到报错
  const cap = alliance.donateDailyCap
  const usedTiers = alliance.donateTiersUsed
  return {
    joined: true,
    title: `[${alliance.tag}] ${alliance.name}`,
    memberText: `${alliance.memberCount}/${alliance.memberCap} 人`,
    fundText: `联盟资金 ${alliance.fund}`,
    contributionText: `我的贡献值 ${alliance.myContribution}`,
    levelText: `Lv${alliance.level}`,
    territoryText: `领地 ${alliance.territoryCount}/${alliance.territoryCap}`,
    expandText: alliance.memberCount >= alliance.memberCap
      ? '人数已满：盟主可用联盟资金扩容（这是中后期最大的资金消耗点）'
      : null,
    donateText: `今日捐献 ${usedTiers.length}/${cap} 档`,
    donateTiersAvailable: Array.from({ length: cap }, (_, tier) => tier)
      .filter((tier) => !usedTiers.includes(tier)),
    myRoleText: allianceRoleText(alliance.myRole),
    // 0 级的那几项也在内：以前这里被服务端滤掉，整个功能在界面上等于不存在
    techs: alliance.techs,
    members: members.map((member: AllianceMember): SocialMemberRow => ({
      id: member.id,
      name: member.name,
      roleText: allianceRoleText(member.role),
      role: String(member.role),
      powerText: `战力 ${member.power}`,
      activeText: activeText(member.lastActiveAt, alliance.serverNow),
      inactive: alliance.serverNow - member.lastActiveAt > INACTIVE_MILLIS,
      // 名字由服务端下发（`squadName`），不拿 `squadId` 去拼：客户端没有小队表，
      // 拼出来就是「分队 squad_17」这种玩家读不懂的黑话（台账 #422，#421 翻页相截图才看见）
      squadText: member.squadName === null ? null : `分队 ${member.squadName}`,
      contributionText: `贡献 ${member.contribution}`,
    })),
  }
}

/** 一条待帮助请求。 */
export function buildHelpRow(request: HelpRequestView, elapsedMs: number): HelpRow {
  return {
    requestId: request.requestId,
    title: `${request.fromPlayerName} 请求帮助`,
    detail: `${request.targetDesc} · 已获帮助 ${request.helpedCount} 次`,
    // remainingSeconds 是服务端在响应时刻算好的快照（协议明写它绝不为负）。
    // 要让它随时间走，减去的是「响应到现在过去了多久」—— 而不是本地时钟与服务端的偏移，
    // 偏移已经在算 elapsedMs 时用掉了，再用一遍等于把校时误差算两次
    countdownText: formatCountdown(
      Math.max(0, request.remainingSeconds * 1000 - Math.max(0, elapsedMs)), '已完成'),
    alreadyHelped: request.alreadyHelped,
  }
}

/** 一条社交事件（推送与离线补偿共用，验收 5 / 12）。 */
export function buildEventRow(event: SocialEventView, offsetMs: number, localNow: number): EventRow {
  const serverNow = localNow + offsetMs
  const ago = Math.max(0, serverNow - event.occurredAt)
  return {
    eventId: event.eventId,
    title: event.title,
    body: event.body,
    timeText: agoText(ago),
    // expired 由服务端给：客户端不自己判断「多久算过期」，
    // 因为响应窗口是玩法数值（支援来得及来不及），不是显示规则
    expired: event.expired,
    coordText: event.coord === null ? null : `(${event.coord.x}, ${event.coord.y})`,
    type: event.type,
  }
}

/** 聊天频道页签名。 */
export function channelText(channel: ChatChannel): string {
  switch (channel) {
    case 'WORLD': return '世界'
    case 'ALLIANCE': return '联盟'
    case 'SQUAD': return '小队'
    case 'PRIVATE': return '私聊'
    default: return channel
  }
}

/** 一条聊天消息的展示文本。 */
export function chatLine(message: ChatMessageView): string {
  return `${message.senderName}：${message.content}`
}

/** 集结视图的展示文本（验收 11：统一出发，兵力合并）。 */
export function rallyText(rally: RallyView, offsetMs: number, localNow: number): string {
  const parts = [
    `${rallyScopeText(rally.scope)}集结 · ${rally.joinedCount}/${rally.maxMembers} 人`,
    `目标 (${rally.targetCoord.x}, ${rally.targetCoord.y})`,
    `已凑兵力 ${rally.totalTroops}`,
  ]
  if (rally.status === 'PREPARING') {
    const remaining = Math.max(0, rally.departAt - (localNow + offsetMs))
    parts.push(remaining > 0 ? `${formatCountdown(remaining, '即将出发')}后统一出发` : '即将统一出发')
  } else {
    parts.push(rallyStatusText(rally.status))
  }
  return parts.join(' · ')
}

/** 我能不能做某件事。权限列表由服务端下发，本函数只是查表。 */
export function canDo(permissions: readonly string[], permission: string): boolean {
  return permissions.includes(permission)
}

// ---------- 纯文本 ----------

export function squadRoleText(role: string): string {
  return role === 'LEADER' ? '队长' : '队员'
}

export function allianceRoleText(role: AllianceRole | string): string {
  switch (role) {
    case 'LEADER': return '盟主'
    case 'OFFICER': return '副盟主'
    case 'ELDER': return '长老'
    case 'MEMBER': return '成员'
    default: return String(role)
  }
}

export function rallyScopeText(scope: RallyScope | string): string {
  switch (scope) {
    case 'SQUAD': return '小队'
    case 'ALLIANCE': return '联盟'
    case 'NATION': return '国家'
    default: return String(scope)
  }
}

export function rallyStatusText(status: RallyStatus | string): string {
  switch (status) {
    case 'PREPARING': return '准备中'
    case 'DEPARTED': return '已出发'
    case 'ARRIVED': return '已到达'
    case 'CANCELLED': return '已取消'
    default: return String(status)
  }
}

/** 事件类型的中文标签。用于列表分组与筛选。 */
export function eventTypeText(type: SocialEventType | string): string {
  switch (type) {
    case 'MEMBER_ATTACKED': return '盟友被攻击'
    case 'SQUAD_DISBANDED': return '小队已解散'
    case 'ALLIANCE_APPLIED': return '入盟申请'
    case 'ALLIANCE_KICKED': return '被踢出联盟'
    case 'SQUAD_KICKED': return '被踢出小队'
    case 'RALLY_INVITED': return '集结邀请'
    case 'RALLY_DEPARTED': return '集结已出发'
    case 'ALLIANCE_TRANSFERRED': return '盟主转让'
    case 'ALLIANCE_DISBANDED': return '联盟已解散'
    case 'HELP_RECEIVED': return '收到帮助'
    case 'HELP_REQUESTED': return '有人请求帮助'
    case 'SQUAD_JOINED': return '有人加入小队'
    case 'ALLIANCE_JOINED': return '入盟申请已通过'
    case 'ALLIANCE_REJECTED': return '入盟申请被拒'
    case 'ALLIANCE_ROLE_SET': return '联盟职务变动'
    case 'NATION_LEFT': return '退出国家'
    case 'NATION_DISBANDED': return '国家已解散'
    case 'PRIVATE_MESSAGE': return '收到私信'
    default: return String(type)
  }
}

/** 定点比例 → 文本，用于展示「已加速 20%」。 */
export function speedupText(fixed: number): string {
  return FixedPoint.percentText(fixed)
}

function activeText(lastActiveAt: number, serverNow: number): string {
  return agoText(Math.max(0, serverNow - lastActiveAt))
}

function agoText(millis: number): string {
  const minutes = Math.floor(millis / 60000)
  if (minutes < 1) {
    return '刚刚'
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  return `${Math.floor(hours / 24)} 天前`
}
