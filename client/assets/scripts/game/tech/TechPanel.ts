/**
 * 职责：研究页的展示数据组装（V03-a-S1 读侧，台账 #264）。
 * 依赖：生成的协议类型 + FixedPoint + 资源名表（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>本模块不判"能不能研究"</b>：`canResearch` 与 `blockedReason` 是服务端的权威答案，
 * 客户端只把原因翻成句子。自己再判一遍学院等级/资源/队列只会与服务器分叉，
 * 症状是"列表里看着能点、点了被拒"（B08/B20 都点过这个失败模式）。
 *
 * <p><b>倒计时用服务端的数</b>：`remainingSeconds` 是响应时刻服务端算好的剩余秒数，
 * 队列行与"下一级耗时"都照它显示，不用本地时钟（铁律 5）—— 玩家改系统时间不该让研究时长变样。
 *
 * <p><b>没解锁的行也画出来</b>：科技树的价值一半在"看得见前面有什么"。
 * 只画可研究的那些，玩家就不知道下一项要多少学院等级 —— 而那是他决定升不升学院的唯一依据。
 */

import * as FixedPoint from '../../core/FixedPoint'
import { resourceName } from '../ui/ResourceNames'
import type {
  ResourceAmount, TechBlockReason, TechEffectAttr, TechListView, TechQueueView,
  TechSchool, TechSpeedUpResp, TechView,
} from '../../net/generated/TechProtocol'

/** 学派名（B20 的四个学派）。表里没有的取值退回枚举名，不显示空白。 */
export function schoolLabel(school: TechSchool): string {
  switch (school) {
    case 'AGRICULTURE':
      return '农政'
    case 'MILITARY':
      return '军事'
    case 'COMMERCE':
      return '商贸'
    case 'FORTIFICATION':
      return '城防'
    default:
      return String(school)
  }
}

/** 效果属性名。改这里就是改全表的行文案，所以集中在一处。 */
export function effectAttrLabel(attr: TechEffectAttr): string {
  switch (attr) {
    case 'WOOD_OUTPUT':
      return '木材产量'
    case 'STONE_OUTPUT':
      return '石料产量'
    case 'IRON_OUTPUT':
      return '铁矿产量'
    case 'GRAIN_OUTPUT':
      return '粮草产量'
    case 'UNIT_ATTACK':
      return '部队攻击'
    case 'UNIT_DEFENSE':
      return '部队防御'
    case 'MARCH_SPEED':
      return '行军速度'
    case 'TRAIN_SPEED':
      return '训练速度'
    case 'BUILD_SPEED':
      return '建造速度'
    case 'HOSPITAL_CAPACITY':
      return '伤兵容量'
    case 'LOAD_CAPACITY':
      return '负重'
    default:
      return String(attr)
  }
}

/** 拒绝原因 → 玩家语言。`NONE` 不是拒绝，回 null（不写一句"没问题"占地方）。 */
export function blockReasonText(reason: TechBlockReason): string | null {
  switch (reason) {
    case 'NONE':
      return null
    case 'ACADEMY_LOW':
      return '学院等级不足'
    case 'QUEUE_BUSY':
      return '研究队列被占用'
    case 'RESOURCE_LOW':
      return '资源不足'
    case 'MAX_LEVEL':
      return '已满级'
    default:
      return String(reason)
  }
}

/** 秒 → 「2 小时 5 分」/「5 分 30 秒」/「30 秒」。都来自服务端给的秒数，不回退到本地时钟。 */
export function remainTextOf(seconds: number): string {
  if (seconds <= 0) {
    return '即将完成'
  }
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (hours > 0) {
    return minutes > 0 ? `${hours} 小时 ${minutes} 分` : `${hours} 小时`
  }
  if (minutes > 0) {
    const rest = seconds % 60
    return rest > 0 ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`
  }
  return `${seconds} 秒`
}

/**
 * 下一级效果文本。定点比率走 `FixedPoint.percentText`（1000 ⇒ "10%"）。
 *
 * `effectValuePerLevelFixed` 为 null = 服务端没给下一级的值（满级）⇒ 回 null，
 * 由调用方显示"已满级"，**不要显示 "+0%"** —— 那读起来像"升了也没用"。
 */
export function effectTextOf(view: TechView): string | null {
  if (view.effectValuePerLevelFixed === null || view.effectValuePerLevelFixed === undefined) {
    return null
  }
  return `${effectAttrLabel(view.effectAttr)} +${FixedPoint.percentText(view.effectValuePerLevelFixed)}/级`
}

/** 成本文本：「木材 600 · 石料 200」。0 量的资源不显示（表里 6 列固定，多数是 0）。 */
export function costTextOf(costs: readonly ResourceAmount[]): string {
  const parts = costs.filter((c) => c.amount > 0).map((c) => `${resourceName(c.type)} ${c.amount}`)
  return parts.length === 0 ? '无需资源' : parts.join(' · ')
}

/**
 * 取消研究的回执那一行。返还比例由服务端按城建同一份配置算（`city_rule_cancel_refund_ratio`），
 * 这里只把 `refund` 念出来 —— 客户端自己按比例重算就是第二个真相，
 * 而"为什么取消建造返 60% 取消研究返 40%"这类问题正是各配一个数字迟早会引来的。
 */
export function techCancelText(techName: string, refund: readonly ResourceAmount[]): string {
  return `已取消「${techName}」 · 退回 ${costTextOf(refund)}`
}

/**
 * 一次研究加速的回执那一行。三个数全部照服务端说的念：减了多少、还剩多少、有没有因此完成 ——
 * 客户端自己拿 `remainingSeconds - reduced` 推一遍，就是第二个真相（服务端算完还会再校验）。
 */
export function techSpeedUpText(resp: TechSpeedUpResp, techName: string): string {
  return resp.finished
    ? `已减 ${resp.reducedSeconds} 秒 · 「${techName}」研究完成`
    : `已减 ${resp.reducedSeconds} 秒 · 还剩 ${resp.remainingSeconds} 秒`
}

/** 队列行：正在研究哪一项、还剩多久。没有在研项时回 null。 */
export function queueTextOf(queue: TechQueueView,
  nameOf: (techId: string) => string): string | null {
  if (queue.techId === null || queue.techId === undefined) {
    return null
  }
  return `正在研究 ${nameOf(queue.techId)} · ${remainTextOf(queue.remainingSeconds)}`
}

/** 研究页的一行。判定字段全部来自服务端，本模块只做文本化。 */
export interface TechRow {
  readonly techId: string
  readonly name: string
  readonly schoolText: string
  /** 「3 / 30 级」 */
  readonly levelText: string
  /** 「木材产量 +4%/级」；满级时为 null */
  readonly effectText: string | null
  /** 「木材 600 · 石料 200」；满级时为「已满级」 */
  readonly costText: string
  /** 「5 分」；满级时为 null */
  readonly timeText: string | null
  /** 服务端的权威判定（客户端不重算） */
  readonly canResearch: boolean
  /** 不能研究的原因（能研究时为 null） */
  readonly reasonText: string | null
}

export interface TechPanelView {
  readonly rows: readonly TechRow[]
  /** 「学院 3 级」 */
  readonly academyText: string
  /** 队列行；无在研项时 null */
  readonly queueText: string | null
  /** 拉取失败或还没拉回来时的说明行 */
  readonly noticeText: string | null
}

/**
 * 组装研究页。
 *
 * <p>**行的顺序原样照抄服务端**（不按学派重排、不按成本排序）：重排等于用客户端的一套口径
 * 覆盖表的编排意图，而玩家对"上一次第三行是哪个"是有肌肉记忆的（与榜单同一条纪律）。
 *
 * @param resp          GET /tech/list 的响应；null = 还没拉回来
 * @param failureNotice 拉取失败时服务端给的理由，原样进说明行（**不清空手里那份**）
 */
export function buildTechPanel(resp: TechListView | null,
  failureNotice?: string | null): TechPanelView {
  if (resp === null || resp === undefined) {
    return {
      rows: [], academyText: '', queueText: null,
      noticeText: failureNotice ?? '研究列表还没拉回来，稍后再试',
    }
  }
  const nameOf = (techId: string): string =>
    resp.techs.find((t) => t.techId === techId)?.name ?? techId
  const rows = resp.techs.map((tech: TechView): TechRow => {
    const maxed = tech.level >= tech.maxLevel
    return {
      techId: tech.techId,
      name: tech.name,
      schoolText: schoolLabel(tech.school),
      levelText: `${tech.level} / ${tech.maxLevel} 级`,
      effectText: maxed ? null : effectTextOf(tech),
      costText: maxed ? '已满级' : costTextOf(tech.nextCost),
      timeText: maxed ? null : remainTextOf(tech.nextTimeSec * 60),
      canResearch: tech.canResearch,
      reasonText: tech.canResearch ? null : blockReasonText(tech.blockedReason),
    }
  })
  return {
    rows,
    academyText: `学院 ${resp.academyLevel} 级`,
    queueText: queueTextOf(resp.queue, nameOf),
    // 空列表要说实话（与装备页同一条）："正在载入…"只用于"还没拉回来"
    noticeText: failureNotice ?? (rows.length === 0 ? '还没有可研究的科技' : null),
  }
}
