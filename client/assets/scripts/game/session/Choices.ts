/**
 * 二级选择器的纯数据组装。AppRoot 只把服务端响应转成候选，场景层只负责画出来。
 * 这里不判断“能不能选”，最终请求仍由服务端校验。
 */

import type { ArmyListResp } from '../../net/generated/ArmyProtocol'
import type { BuildOptionView, CityListResp } from '../../net/generated/CityProtocol'
import type { HeroListResp } from '../../net/generated/HeroProtocol'
import type { StageUnit } from '../../net/generated/StageProtocol'
import type { ShareChannel } from '../../net/generated/BattleProtocol'
import type { ReportReason } from '../../net/generated/SocialProtocol'
import { buildingTypeName } from '../ui/ResourceNames'

export interface ChoiceOption {
  readonly id: string
  readonly label: string
  readonly detail: string
}

export interface SpeedupChoice extends ChoiceOption {
  readonly targetId: string
}

export interface LineupChoice extends ChoiceOption {
  readonly heroes: readonly string[]
  readonly units: readonly StageUnit[]
}

/** 战报分享的目标频道（B22 §一 2）。只有小队 / 联盟两个：世界频道是陌生人广场，不该贴战报。 */
export interface ShareChannelChoice extends ChoiceOption {
  readonly channel: ShareChannel
}

/**
 * 分享频道的候选。**不按"我在不在这个组织里"过滤**：那要在客户端存一份组织关系的副本，
 * 副本过期时玩家会看到两个都点不动的按钮，而服务端本来就会给出一句更准的理由
 * （未入盟 → `SOCIAL_CHAT_CHANNEL_INVALID`）。与聊天页签的四个频道按钮同一条口径。
 */
export function buildShareChannelChoices(): readonly ShareChannelChoice[] {
  return [
    { id: 'ALLIANCE', channel: 'ALLIANCE', label: '分享到联盟', detail: '本盟成员都能点开这场回放' },
    { id: 'SQUAD', channel: 'SQUAD', label: '分享到小队', detail: '本队成员都能点开这场回放' },
  ]
}

/** 未放置建筑候选。地块能否放置由玩家点选坐标后交给服务端判定。 */
export function buildBuildChoices(city: CityListResp | null): readonly ChoiceOption[] {
  if (city === null) {
    return []
  }
  return (city.buildOptions ?? []).map((option: BuildOptionView) => ({
    id: option.configId,
    label: option.name,
    detail: buildOptionDetail(option),
  }))
}

/** 加速道具当前可选目标：正在升级的建筑 + 正在训练的兵种。 */
export function buildSpeedupChoices(city: CityListResp | null,
                                    army: ArmyListResp | null): readonly SpeedupChoice[] {
  const out: SpeedupChoice[] = []
  if (city !== null) {
    for (const building of city.buildings ?? []) {
      if (building.status !== 'UPGRADING' || building.remainingSeconds === null
          || building.remainingSeconds <= 0) {
        continue
      }
      out.push({
        id: building.id,
        targetId: building.id,
        label: `${building.name} Lv${building.level}`,
        detail: `建筑升级 · 剩余 ${formatSeconds(building.remainingSeconds)}`,
      })
    }
  }
  if (army !== null) {
    for (const unit of army.units ?? []) {
      if (unit.training <= 0 || unit.remainingSeconds === null
          || unit.remainingSeconds <= 0) {
        continue
      }
      out.push({
        id: unit.unitId,
        targetId: unit.unitId,
        label: `${unit.name} ×${unit.training}`,
        detail: `兵种训练 · 剩余 ${formatSeconds(unit.remainingSeconds)}`,
      })
    }
  }
  return out
}

/**
 * 关卡出战候选：每个已编成的主将预设一项，携带当前全部可用兵力。
 * 兵种限制与体力仍由服务端裁定，客户端不在这里复制一份规则。
 */
export function buildLineupChoices(heroes: HeroListResp | null,
                                   army: ArmyListResp | null): readonly LineupChoice[] {
  if (heroes === null) {
    return []
  }
  const nameById = new Map<string, string>()
  for (const hero of heroes.heroes ?? []) {
    nameById.set(hero.heroId, hero.name)
  }
  const units: StageUnit[] = army === null
    ? []
    : (army.units ?? [])
      .filter((unit) => unit.count > 0)
      .map((unit) => ({ unitId: unit.unitId, count: unit.count }))
  const troopCount = units.reduce((sum, unit) => sum + unit.count, 0)

  const out: LineupChoice[] = []
  for (const lineup of heroes.lineups ?? []) {
    if (lineup.main === null) {
      continue
    }
    const heroIds = [lineup.main, lineup.sub1, lineup.sub2]
      .filter((heroId): heroId is string => heroId !== null)
    const names = [lineup.main, lineup.sub1, lineup.sub2].map((heroId) => {
      if (heroId === null) {
        return '空位'
      }
      return nameById.get(heroId) ?? heroId
    })
    out.push({
      id: `lineup:${lineup.presetIndex}`,
      label: `编队 ${lineup.presetIndex + 1} · ${names[0]}`,
      detail: `${names.join(' / ')} · 兵力 ${troopCount} · 统帅 ${lineup.bonus.commandValue}`,
      heroes: heroIds,
      units,
    })
  }
  return out
}

function buildOptionDetail(option: BuildOptionView): string {
  // `option.type` 是配置表的枚举原文（RESOURCE / MILITARY …），直接印出来就是玩家读到英文枚举 ——
  // 与收口清单 #255 的 `main_city` 同类。走 `buildingTypeName` 这一份映射。
  const parts = [buildingTypeName(option.type), `需要主城 ${option.requireMainLevel} 级`]
  if (option.requireBuilding !== null && option.requireBuilding.length > 0) {
    // **显示名走服务端下发的 `requireBuildingName`，绝不印 `requireBuilding`**：
    // 后者是 building.json 的行 id，拼上屏就是「前置 main_city」——#255 同族。
    // 旧服务端缺这一位时退成「前置建筑」这句人话：少一点提示只是信息少，多一个编号是界面在说黑话
    // （与 `QuestPanel.preQuestLabel` 同一条口径）。
    const name = option.requireBuildingName
    parts.push(name !== null && name.length > 0 ? `前置 ${name}` : '前置建筑')
  }
  return parts.join(' · ')
}

function formatSeconds(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  const rest = safe % 60
  if (hours > 0) {
    return `${hours}小时${minutes}分`
  }
  if (minutes > 0) {
    return `${minutes}分${rest}秒`
  }
  return `${rest}秒`
}

/** 聊天消息行上的动作（B22 §一 3）。举报的四种原因与拉黑合并成一层选择：多一层嵌套选择器只会让人放弃。 */
export interface ChatActionChoice extends ChoiceOption {
  /** 'REPORT' 走举报（带 reason），其余分别走拉黑名单、关注与私聊 */
  readonly kind: 'REPORT' | 'BLOCK' | 'UNBLOCK' | 'FOLLOW' | 'UNFOLLOW' | 'PRIVATE'
  readonly reason: ReportReason | null
}

/**
 * 一条别人发的消息能做什么。
 *
 * <p>**举报的原因直接摆在选项里**（而不是先选"举报"再选原因）：举报是一个需要当场完成的小动作，
 * 两层选择器的第二层会让一半人放弃 —— 而放弃的那一半就变成了运营永远看不到的骚扰。
 *
 * <p>`blocked` 由调用方从服务端名单里读（组合根持有），这里不猜。
 */
export function buildChatActionChoices(blocked: boolean, following = false): readonly ChatActionChoice[] {
  return [
    { id: 'REPORT_ABUSE', kind: 'REPORT', reason: 'ABUSE', label: '举报：辱骂', detail: '人身攻击、恶意谩骂' },
    { id: 'REPORT_SPAM', kind: 'REPORT', reason: 'SPAM', label: '举报：刷屏', detail: '广告、复读、无意义刷屏' },
    { id: 'REPORT_CHEAT', kind: 'REPORT', reason: 'CHEAT_SUSPECT', label: '举报：疑似作弊', detail: '交给运营与反作弊判定' },
    { id: 'REPORT_OTHER', kind: 'REPORT', reason: 'OTHER', label: '举报：其他', detail: '说不上来但觉得不对' },
    { id: 'PRIVATE', kind: 'PRIVATE', reason: null, label: '私聊他', detail: '直接开一段一对一会话' },
    following
      ? { id: 'UNFOLLOW', kind: 'UNFOLLOW', reason: null, label: '取消关注', detail: '不再出现在我的关注列表里' }
      : { id: 'FOLLOW', kind: 'FOLLOW', reason: null, label: '关注他', detail: '看他在不在线，随时找他说话' },
    blocked
      ? { id: 'UNBLOCK', kind: 'UNBLOCK', reason: null, label: '取消拉黑', detail: '恢复与他的私聊' }
      : { id: 'BLOCK', kind: 'BLOCK', reason: null, label: '拉黑', detail: '不再看到他的消息（不影响战斗）' },
  ]
}
