/**
 * 职责：把行军渲染帧组装成玩家可读的列表，并给出可执行动作（B07 §2/§4，验收 7）。
 * 依赖：WorldViewModel 的 MarchRender 类型（引擎无关，可脱离 Cocos 跑单测）。
 *
 * <p><b>本模块只做展示映射，不复制服务端规则</b>：动作资格只按状态枚举给出入口，
 * 真正的“能不能召回 / 能不能领取”仍由 `/world/recall` 与 `/world/collectGather` 裁决。
 * 客户端把交战中、返程中这类明显没有动作的状态标成“无操作”，避免玩家点一个必然失败的按钮。
 */

import type { MarchAction, MarchStatus } from '../../net/generated/WorldProtocol'
import type { MarchRender } from './WorldViewModel'

export type MarchPanelAction = 'recall' | 'collect'

/** 行军面板中的一行。action 为 null 时场景不显示动作按钮。 */
export interface MarchPanelRow {
  readonly marchId: string
  readonly title: string
  readonly detail: string
  readonly remainingText: string
  readonly action: MarchPanelAction | null
  readonly actionText: string | null
}

/**
 * 组装列表。最多画 `maxRows` 行，避免行军数超出并发上限时把面板撑出屏幕。
 * 顺序照搬服务端响应，不按时间或状态重排，玩家看到的顺序在每次刷新之间保持稳定。
 */
export function buildMarchPanel(marches: readonly MarchRender[], maxRows = 3): MarchPanelRow[] {
  const rows: MarchPanelRow[] = []
  for (const march of marches.slice(0, Math.max(0, maxRows))) {
    const action = actionForStatus(march.status)
    rows.push({
      marchId: march.marchId,
      title: `${marchActionText(march.action)} · 目标 (${march.to.x}, ${march.to.y})`,
      detail: `${marchStatusText(march.status)} · 当前 (${march.x}, ${march.y}) · 载重 ${march.load}/${march.loadCap}`,
      remainingText: marchRemainingText(march),
      action,
      actionText: action === 'collect' ? '收取' : action === 'recall' ? '召回' : null,
    })
  }
  return rows
}

/**
 * 动作入口与服务端 `March.recall` / `MarchAppService.collectGather` 的状态约束一致：
 * - 采集中的队伍优先给“收取”，因为领取会结算已采资源并开始返程；
 * - 前往中/驻扎中的队伍可以召回；
 * - 返程与交战中不给入口，服务端也会拒绝。
 */
export function actionForStatus(status: MarchStatus): MarchPanelAction | null {
  if (status === 'GATHERING') {
    return 'collect'
  }
  if (status === 'MARCHING' || status === 'STATIONED') {
    return 'recall'
  }
  return null
}

export function marchStatusText(status: MarchStatus): string {
  switch (status) {
    case 'MARCHING':
      return '行军中'
    case 'STATIONED':
      return '已驻扎'
    case 'GATHERING':
      return '采集中'
    case 'RETURNING':
      return '返程中'
    case 'FIGHTING':
      return '交战中'
    default:
      return String(status)
  }
}

export function marchActionText(action: MarchAction): string {
  switch (action) {
    case 'ATTACK':
      return '进攻'
    case 'GATHER':
      return '采集'
    case 'STATION':
      return '驻扎'
    case 'SCOUT':
      return '侦查'
    case 'GARRISON':
      return '驻防'
    default:
      return String(action)
  }
}

/** 剩余时间只做文本格式化；到达与状态推进仍由服务端裁决。 */
export function marchRemainingText(march: MarchRender): string {
  if (march.status === 'GATHERING') {
    return march.gatherRemainingMs > 0
      ? `${formatDuration(march.gatherRemainingMs)}后采满`
      : '已可收取'
  }
  if (march.status === 'RETURNING') {
    return `${formatDuration(march.remainingMs)}后回城`
  }
  if (march.status === 'MARCHING') {
    return `${formatDuration(march.remainingMs)}后到达`
  }
  if (march.status === 'STATIONED') {
    return '已抵达'
  }
  if (march.status === 'FIGHTING') {
    return '战斗结算中'
  }
  return ''
}

/** 展示用时长：小于一小时显示分秒，超过一小时显示小时分钟。 */
export function formatDuration(millis: number): string {
  const totalSeconds = Math.max(0, Math.floor(millis / 1000))
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  if (hours > 0) {
    return `${hours}小时${minutes}分`
  }
  return minutes > 0 ? `${minutes}分${seconds}秒` : `${seconds}秒`
}
