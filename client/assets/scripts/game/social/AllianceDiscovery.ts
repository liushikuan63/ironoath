/**
 * 职责：「可申请联盟」那一屏的判定（B26 S6）—— 纯逻辑，不碰引擎。
 * 依赖：只有协议类型（`net/generated`）。
 *
 * <p>三件事都只看服务端下发的结论：
 * ① **满不满、申请过没有都不自己算**：人数上限含盟主扩容后的值，那份账在服务端；
 *    自己拿 memberCount 与 memberCap 比会漏掉"刚扩过容"和"我已经在申请列表里"两种真实状态。
 * ② **一屏画不下要说出来**：列表是有界的（global.ALLIANCE_LIST_LIMIT），
 *    把前 N 个当成全部来画就是假话 —— 所以那句「共 X 个，只显示前 Y 个」由 total 与 limit 一起算。
 * ③ 灰态一律配一句原因，理由用玩家读得懂的话，不出现 id 与字段名。
 */
import type { AllianceDiscoveryView, AllianceListResp } from '../../net/generated/SocialProtocol'

/** 一行联盟。 */
export interface DiscoveryRow {
  readonly id: string
  /** 「[TS] 铁誓」 */
  readonly titleText: string
  /** 「Lv3 · 4/30 人」 */
  readonly memberText: string
  /** 按钮文字：申请加入 / 已申请 / 已满 */
  readonly actionText: string
  readonly enabled: boolean
  /** 灰的时候那句原因（亮的时候是空串） */
  readonly reason: string
}

export interface DiscoveryView {
  readonly rows: readonly DiscoveryRow[]
  /** 列表上方/下方那句总量说明。空串表示不需要说明（全部都已画出）。 */
  readonly notice: string
}

export const EMPTY_DISCOVERY: DiscoveryView = { rows: [], notice: '' }

export function buildDiscovery(resp: AllianceListResp | null): DiscoveryView {
  if (resp === null) {
    return { rows: [], notice: '可申请联盟读取中' }
  }
  const rows = resp.alliances.map((a: AllianceDiscoveryView): DiscoveryRow => ({
    id: a.id,
    titleText: `[${a.tag}] ${a.name}`,
    memberText: `Lv${a.level} · ${a.memberCount}/${a.memberCap} 人`,
    // 三种状态各有各的按钮文字：只把「申请加入」灰掉而不说原因，玩家会以为是网络或按钮坏了
    actionText: a.applied ? '已申请' : a.full ? '已满' : '申请加入',
    enabled: !a.applied && !a.full,
    reason: a.applied ? '等盟主或官员审核' : a.full ? '位置满了，等他们扩容或有人离开' : '',
  }))
  return {
    rows,
    notice: resp.total > resp.alliances.length
      ? `共 ${resp.total} 个联盟，这里只显示前 ${resp.alliances.length} 个`
      : resp.total === 0 ? '还没有人建立联盟' : '',
  }
}

/** 点某一行的按钮之前，客户端能确定的唯一一件事：这一行现在能不能发（其余判断都在服务端）。 */
export function canApply(view: DiscoveryView, id: string): boolean {
  return view.rows.some(row => row.id === id && row.enabled)
}
