/**
 * 职责：集结面板的展示数据组装（V02-S1）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不做任何判定</b>（铁律 2）：谁能加入、还差几个人、这波有多少兵，
 * 全部来自 `GET /rally/list` 下发的那一份（`RallyView.joinedCount` / `totalTroops` / `members`）。
 * 客户端只做两件它必须自己做的事：把服务端两个时刻相减算出倒计时（铁律 5，不用本地时钟），
 * 以及按"我在不在这支队伍的成员里"决定那颗按钮是「加入」还是「退出」。
 *
 * <p><b>为什么"我参没参"要在客户端判</b>：协议里 `members` 是参与者 id 列表（按加入时间升序），
 * 而"我是谁"只有客户端知道（playerId 在登录响应里）。这不是业务判定 ——
 * 服务端已经给了权威名单，这里只是拿自己的 id 去对一份已下发的名单。
 *
 * <p><b>发起人那一行不给「退出」给「取消」</b>：服务端是两条不同的路（`/rally/quit` 与 `/rally/cancel`），
 * 发起人退出等于把整支集结解散掉（其余人承诺的兵要退回去）—— 写成同一个按钮会让发起人以为
 * 自己只是"退出"，而实际效果是把大家召集的这波取消了。两个动作、两个词，不许含糊。
 */

import type { RallyListResp, RallyView } from '../../net/generated/SocialProtocol'

/** 一行集结。 */
export interface RallyRow {
  readonly rallyId: string
  /** 「小队集结」/「联盟集结」/「国家集结」 */
  readonly scopeText: string
  /** 「目标：野外怪 (433,95)」 */
  readonly targetText: string
  /** 「已加入 3/10 人 · 兵力 12400」 */
  readonly partyText: string
  /** 「准备还剩 2 分 35 秒」；已过准备时刻给「即将出发」 */
  readonly remainText: string
  /** 我在不在这支队伍的成员里 */
  readonly mine: boolean
  /** 我是发起人 */
  readonly initiatedByMe: boolean
  /** 按钮文案；null = 这一行没有可点的动作（比如满员且我没参） */
  readonly actionText: string | null
  /** 点了要发什么：join / quit / cancel；null = 不发请求 */
  readonly action: 'join' | 'quit' | 'cancel' | null
  /** action 为 null 时的那句原因（给玩家一个解释，而不是一颗没有文案的灰按钮） */
  readonly blockedReason: string | null
}

/** 整块集结面板。 */
export interface RallyPanelView {
  readonly rows: readonly RallyRow[]
  /** 一句话说明这一页是什么（空列表时也画，玩家才知道这里该有什么） */
  readonly headerText: string
  /** 一支都没有时的那句话；有则 null */
  readonly emptyText: string | null
  /** 列表还没拉回来时那句；拉到了为 null */
  readonly noticeText: string | null
}

/** 发起层级的中文名。表里没有的取值退回枚举名（不显示空白）。 */
export function scopeLabel(scope: RallyView['scope']): string {
  switch (scope) {
    case 'SQUAD':
      return '小队集结'
    case 'ALLIANCE':
      return '联盟集结'
    case 'NATION':
      return '国家集结'
    default:
      return String(scope)
  }
}

/**
 * 目标类型的中文名（玩家语言，不是枚举名）。
 * 分支必须覆盖 `SocialTargetType` 的全部五个取值 —— 写这一版时先核了生成类型
 * （EMPTY/MONSTER/RESOURCE/PLAYER_CITY/ALLIANCE_BUILDING），
 * 少一个分支的症状是"地图上明明是个采集点，列表里显示 RESOURCE"这种半英文。
 */
export function targetLabel(targetType: RallyView['targetType']): string {
  switch (targetType) {
    case 'EMPTY':
      return '空地'
    case 'MONSTER':
      return '野外怪'
    case 'RESOURCE':
      return '资源点'
    case 'PLAYER_CITY':
      return '敌方城池'
    case 'ALLIANCE_BUILDING':
      return '联盟建筑'
    default:
      return String(targetType)
  }
}

/** 「(433,95)」——坐标原样来自服务端，客户端不做任何换算。 */
export function coordText(coord: RallyView['targetCoord']): string {
  return `(${coord.x},${coord.y})`
}

/**
 * 剩余时间：**服务端两个时刻相减**（`prepareUntil - serverNow`），绝不用本地时钟。
 * 超过一分钟给「分 秒」，不足一分钟给「秒」，已过点给「即将出发」（不显示负数）。
 */
export function remainTextOf(rally: RallyView): string {
  const remainMs = rally.prepareUntil - rally.serverNow
  if (remainMs <= 0) {
    return '即将出发'
  }
  const totalSeconds = Math.floor(remainMs / 1000)
  if (totalSeconds < 60) {
    return `准备还剩 ${totalSeconds} 秒`
  }
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `准备还剩 ${minutes} 分 ${seconds} 秒`
}

/** 一行。`myPlayerId` 为空时按"没参"处理（不知道我是谁就别给"退出"）。 */
export function buildRallyRow(rally: RallyView, myPlayerId: string): RallyRow {
  const mine = myPlayerId !== '' && rally.members.indexOf(myPlayerId) >= 0
  const initiatedByMe = myPlayerId !== '' && rally.initiatorId === myPlayerId
  const full = rally.joinedCount >= rally.maxMembers
  let action: RallyRow['action'] = null
  let actionText: string | null = null
  let blockedReason: string | null = null
  if (initiatedByMe) {
    // 发起人：能取消，不能"退出"（那会解散整支队伍，服务端也是另一条路）
    action = 'cancel'
    actionText = '取消集结'
  } else if (mine) {
    action = 'quit'
    actionText = '退出'
  } else if (!full) {
    action = 'join'
    actionText = '加入'
  } else {
    blockedReason = `已满 ${rally.maxMembers} 人`
  }
  return {
    rallyId: rally.rallyId,
    scopeText: scopeLabel(rally.scope),
    targetText: `目标：${targetLabel(rally.targetType)} ${coordText(rally.targetCoord)}`,
    partyText: `已加入 ${rally.joinedCount}/${rally.maxMembers} 人 · 兵力 ${rally.totalTroops}`,
    remainText: remainTextOf(rally),
    mine,
    initiatedByMe,
    actionText,
    action,
    blockedReason,
  }
}

/**
 * 组装整块视图。
 *
 * @param resp       GET /rally/list 的响应；null = 还没拉回来（画一句说明，不画一个空列表装"没有集结"）
 * @param myPlayerId 我自己的玩家 id（来自登录响应）；空串时所有行都按"没参"处理
 */
export function buildRallyPanel(resp: RallyListResp | null, myPlayerId: string): RallyPanelView {
  if (resp === null || resp === undefined) {
    return {
      rows: [], headerText: '本队与本盟进行中的集结', emptyText: null,
      noticeText: '集结列表还没拉回来，稍后再试',
    }
  }
  const rows = resp.rallies.map((rally) => buildRallyRow(rally, myPlayerId))
  return {
    rows,
    headerText: '本队与本盟进行中的集结',
    emptyText: rows.length === 0
      ? '现在没有进行中的集结 —— 在世界地图上选一个目标就能发起（要先加入小队或联盟）'
      : null,
    noticeText: null,
  }
}

/** 加入请求的业务字段（requestId 由传输层注入）。承诺的兵力由编成界面给，见 `RallyJoinReq`。 */
export interface RallyActionBody {
  readonly rallyId: string
}

/** 一条动作要发的路径：三种动作三条路，绝不共用一个端点（服务端就是三个）。 */
export function actionPath(action: RallyRow['action']): string | null {
  switch (action) {
    case 'join':
      return '/rally/join'
    case 'quit':
      return '/rally/quit'
    case 'cancel':
      return '/rally/cancel'
    default:
      return null
  }
}
