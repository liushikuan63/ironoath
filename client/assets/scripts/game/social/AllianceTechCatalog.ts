/**
 * 职责：联盟科技那几行的判定（B26 S9）—— 纯逻辑，不碰引擎。
 * 依赖：只有协议类型（`net/generated`）。
 *
 * <p>三件事都只看服务端下发的结论：
 * ① **名字与价格都用表里那一份**：客户端没有 `alliance_tech` 表，自己拿 `techId` 翻名字或
 *    自己乘 `costBaseDonation` 都是第二真源（同族见收口清单 #255 / #281 / #303）。
 * ② **能不能研究是两条门拼出来的，各说各的**：「此刻研究得动吗」（上限与资金，服务端
 *    `canResearch`）与「你这个职位能不能研究」（`/social/permissions` 的 `RESEARCH_TECH`）。
 *    合成一条的话，职位不够的人会看见一句"资金不足"，然后去捐钱——白捐。
 * ③ 灰态一律配一句人话原因，不出现 id 与字段名。
 */
import type { AllianceTechView } from '../../net/generated/SocialProtocol'

export interface TechRow {
  readonly id: string
  /** 「联盟锋刃」 */
  readonly titleText: string
  /** 「Lv1/40 · 下一级 2000」 */
  readonly detailText: string
  /** 按钮文字：研究 / 已满 / 钱不够 / 不能研究 */
  readonly actionText: string
  readonly enabled: boolean
  /** 灰的时候那句原因（亮的时候是 null） */
  readonly reason: string | null
}

/** 职位门：来自 PermissionGates 的 gate()，{allowed, reason}。 */
export interface ResearchGate {
  readonly allowed: boolean
  readonly reason: string | null
}

export function buildTechRows(techs: readonly AllianceTechView[],
  gate: ResearchGate): TechRow[] {
  return techs.map((tech: AllianceTechView): TechRow => {
    // 三条门按"最该先告诉玩家的那一条"排：职位不能研究 → 上限 → 资金。
    // 顺序反了会让人去做一件没用的事（钱不够其实是因为压根轮不到他研究）。
    if (!gate.allowed) {
      return {
        id: tech.techId, titleText: tech.name,
        detailText: `Lv${tech.level}/${tech.levelCap} · 下一级 ${tech.nextLevelCost}`,
        actionText: '不能研究', enabled: false, reason: gate.reason ?? '你当前的职位不能研究科技',
      }
    }
    if (!tech.canResearch && tech.reason !== null && tech.reason !== undefined) {
      return {
        id: tech.techId, titleText: tech.name,
        detailText: `Lv${tech.level}/${tech.levelCap} · 下一级 ${tech.nextLevelCost}`,
        actionText: tech.level >= tech.levelCap ? '已满' : '钱不够',
        enabled: false, reason: tech.reason,
      }
    }
    return {
      id: tech.techId, titleText: tech.name,
      detailText: `Lv${tech.level}/${tech.levelCap} · 下一级 ${tech.nextLevelCost}`,
      actionText: '研究', enabled: true, reason: null,
    }
  })
}
