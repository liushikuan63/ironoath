/**
 * 职责：「可加入小队」那一屏的判定（B26 S7）—— 纯逻辑，不碰引擎。
 * 依赖：只有协议类型（`net/generated`）。
 *
 * <p>与 {@link AllianceDiscovery} 同一条纪律，只有一处不同：小队加入**不需要审核**，
 * 所以没有「已申请」那一态，只有「能加」与「满了」。
 * ① 满不满只看服务端那个布尔：小队上限的第二档挂在**队长主城等级**上，那份读数客户端拿不到。
 * ② 列表有界（global.SQUAD_LIST_LIMIT），把前 N 支当成全部画就是假话。
 * ③ 灰态配一句人话原因，不出现 id 与字段名。
 */
import type { SquadDiscoveryView, SquadListResp } from '../../net/generated/SocialProtocol'

/** 一行小队。 */
export interface SquadRow {
  readonly id: string
  /** 「铁血队」 */
  readonly titleText: string
  /** 「Lv2 · 3/5 人」 */
  readonly memberText: string
  /** 按钮文字：加入 / 已满 */
  readonly actionText: string
  readonly enabled: boolean
  /** 灰的时候那句原因（亮的时候是空串） */
  readonly reason: string
}

export interface SquadListView {
  readonly rows: readonly SquadRow[]
  /** 那句总量说明。空串表示不需要说明（全部都已画出）。 */
  readonly notice: string
}

export const EMPTY_SQUAD_DISCOVERY: SquadListView = { rows: [], notice: '' }

export function buildSquadDiscovery(resp: SquadListResp | null): SquadListView {
  if (resp === null) {
    return { rows: [], notice: '可加入小队读取中' }
  }
  const rows = resp.squads.map((s: SquadDiscoveryView): SquadRow => ({
    id: s.id,
    titleText: s.name,
    memberText: `Lv${s.level} · ${s.memberCount}/${s.memberCap} 人`,
    actionText: s.full ? '已满' : '加入',
    enabled: !s.full,
    // 上限由「小队等级 + 队长主城等级」两档决定，所以那句原因指向队长身上
    reason: s.full ? '位置满了，等队长把主城提上去或有人离开' : '',
  }))
  return {
    rows,
    notice: resp.total > resp.squads.length
      ? `共 ${resp.total} 支小队，这里只显示前 ${resp.squads.length} 支`
      : resp.total === 0 ? '还没有人建立小队' : '',
  }
}

/** 点某一行的按钮之前，客户端能确定的唯一一件事：这一行现在能不能发（其余判断都在服务端）。 */
export function canJoin(view: SquadListView, id: string): boolean {
  return view.rows.some(row => row.id === id && row.enabled)
}
