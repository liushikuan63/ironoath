/**
 * 职责：国家面板的 S2 三块 —— 国家科技 / 外交 / 任命（B13 §2、§3、§5；B20 块③）。
 * 依赖：生成的协议类型 + `game/tech/TechPanel` 的两份既有文案函数（引擎无关，可脱离 Cocos 跑单测）。
 * 依赖：无 Cocos。
 *
 * <p><b>三块共同的纪律：只看服务端下发的结论</b>。
 * ① 科技：`canResearch` / `blockedReason` / `nextCostTreasury` 全部是服务端算好的，
 *    客户端不按国家等级与国库余额判第二遍（那是第二个家）。
 * ② 外交：`/nation/diplomacy` 的 `allRelations` 是变更后回的那张表 —— **服务端没有只读的关系端点**，
 *    所以"还没打过一次交道"的国家在屏上是「未知」，而不是「中立」猜出来的。
 * ③ 任命：权限位在 `role_permission` 表里（APPOINT_OFFICE），**客户端拿不到那张表**，
 *    所以客户端不预判"谁任命得了"；被拒时把服务端那句原样显示。
 *
 * <p><b>复用而不是重抄</b>：`schoolLabel` 与 `effectAttrLabel` 直接取个人科技那份
 * （契约里明写两个 `TechSchool` / `TechEffectAttr` 逐字段同形、故意不分叉），
 * 定点比率走 `FixedPoint.percentText`。抄第二份就是留一个将来必然分叉的口径（#281 那一族）。
 */
import type { NationRelationView } from '../../net/generated/NationProtocol'
import type { NationTechListView, NationTechView } from '../../net/generated/NationTechProtocol'
import { effectAttrLabel, schoolLabel } from '../tech/TechPanel'
import { amountText } from './NationPanel'

/** 拒绝原因 → 玩家语言（`NationTechBlockReason` 与个人那枚**刻意不合并**，见契约注释）。 */
export function nationTechBlockText(reason: string): string | null {
  switch (reason) {
    case 'NONE':
      return null
    case 'NATION_LOW':
      return '国家等级不够'
    case 'TREASURY_LOW':
      return '国库余额不足'
    case 'OFFICER_LIMIT':
      return '本周国库支出额度已经用完，等下周'
    case 'MAX_LEVEL':
      return '已经满级'
    case 'NOT_OFFICER':
      return '你没有研究国家科技的权限'
    default:
      return '此刻研究不了这一行'
  }
}

/** 外交关系 → 中文。`DiplomacyRelation` 四个取值（契约里的 B13 §5）。 */
export function diplomacyLabel(relation: string): string {
  switch (relation) {
    case 'ALLIED':
      return '盟约'
    case 'HOSTILE':
      return '敌对'
    case 'NEUTRAL':
      return '中立'
    case 'TRIBUTARY':
      return '附庸'
    default:
      return '未知关系'
  }
}

/** 可选的关系（面板上四颗键的顺序 = 契约里 enum 的顺序）。 */
export const DIPLOMACY_OPTIONS: readonly { key: string; label: string; note: string }[] = [
  { key: 'ALLIED', label: '盟约', note: '双方都记着盟约才成立，盟约之间不能互相攻击' },
  { key: 'TRIBUTARY', label: '附庸', note: '同样要双方都记着才成立' },
  { key: 'NEUTRAL', label: '中立', note: '随时可以改回来' },
  { key: 'HOSTILE', label: '敌对', note: '敌对之间才可以互相攻击' },
]

/**
 * 可任命的官职。
 *
 * <p>**刻意不给国王与议员两席**：服务端 `Nation.appoint` 对这两席当场拒绝
 * （`NationAppService.appoint` 的注释写明"国王与议员都没有任命路径"）——
 * 摆两颗按下去必然失败的键，就是 UI 在骗玩家。
 * 这不是"客户端判权限"：那一条讲的是"谁能任命别人"，这里讲的是"哪些官职有任命入口"。
 */
export const APPOINTABLE_OFFICES: readonly { key: string; label: string }[] = [
  { key: 'PRIME_MINISTER', label: '首相' },
  { key: 'GENERAL', label: '大将军' },
  { key: 'MINISTER', label: '内政官' },
  { key: 'DIPLOMAT', label: '外交官' },
]

/** 一行国家科技。 */
export interface NationTechRowView {
  readonly key: string
  readonly titleText: string
  /** 「Lv1/5 · 下一级 12,000」 */
  readonly detailText: string
  /** 「木材产量 +4%/级」；服务端没给值（满级）时为 null */
  readonly effectText: string | null
  readonly actionText: string
  readonly enabled: boolean
  readonly reason: string | null
}

/** 国家科技那一整块。`resp` 为 null = 这一次没读到（与"表里一行都没有"是两回事）。 */
export interface NationTechSection {
  readonly headerText: string | null
  readonly rows: readonly NationTechRowView[]
  readonly emptyText: string | null
}

/**
 * 一行 → 一行视图。
 *
 * <p>三道门**按"最该先告诉玩家"的那一条排**（与 `AllianceTechCatalog` 同一条理由）：
 * 权限 → 已满级 → 前置等级 → 国库。反了会让人去做一件没用的事
 * （国库不够其实是因为压根轮不到他研究）。
 */
function techRow(tech: NationTechView): NationTechRowView {
  const level = `Lv${tech.level}/${tech.maxLevel}`
  const detailText = tech.level >= tech.maxLevel
    ? level
    : `${level} · 下一级 ${amountText(tech.nextCostTreasury)}`
  const reason = nationTechBlockText(tech.blockedReason)
  return {
    key: tech.techId,
    titleText: `${tech.name} · ${schoolLabel(tech.school)}`,
    detailText,
    effectText: tech.effectValuePerLevelFixed === null || tech.effectValuePerLevelFixed === undefined
      ? null
      : `${effectAttrLabel(tech.effectAttr)} +${(tech.effectValuePerLevelFixed / 100).toFixed(0)}%/级`,
    // **动作文字直接照 `canResearch` 说**，客户端不自己判：满级说"已满"、拦着就说"研究不了"，
    // 具体原因在 reason 那一句里 —— 两条信息分开才不会被"按钮写着能点但点不动"糊弄过去
    actionText: tech.canResearch ? '研究一级' : (reason ?? '研究不了'),
    enabled: tech.canResearch,
    reason,
  }
}

/** 科技那一块。`schoolLabel` 也摆上（学派是界面分组的依据，而分组由服务端给的名决定）。 */
export function buildNationTechSection(resp: NationTechListView | null): NationTechSection {
  if (resp === null) {
    return { headerText: null, rows: [], emptyText: '国家科技这一次没读到（与"一项都没研究"是两回事）' }
  }
  const rows = (resp.techs ?? []).map(tech => techRow(tech))
  return {
    headerText: `${resp.nationName} · 国家等级 Lv${resp.nationLevel} · 国库 ${amountText(resp.treasury)}`,
    rows,
    emptyText: rows.length === 0 ? '这张表里还没有国家科技' : null,
  }
}

/** 外交关系的一行。 */
export interface DiplomacyRowView {
  readonly key: string
  readonly name: string
  /** 当前关系的中文；**没有记录时是 `null`**（不是猜一个"中立"）。 */
  readonly relationText: string | null
}

/**
 * 关系表。
 *
 * <p>`allRelations` 只在**变更之后**才有（`/nation/diplomacy` 是唯一入口，而它是个写口），
 * 所以第一次打开这一页之前是空表 —— 面板要照实说"还没有打过一次交道"，
 * 而不是给每个国家填一个"中立"（那会让人以为已经签了一份中立条约）。
 */
export function buildDiplomacyRows(relations: readonly NationRelationView[] | null): DiplomacyRowView[] {
  if (relations === null) {
    return []
  }
  return relations.map(rel => ({
    key: rel.nationId,
    name: rel.nationName,
    relationText: diplomacyLabel(rel.relation),
  }))
}

/** 任命的一行：被任命者（昵称）+ 四颗官职键。 */
export interface AppointRowView {
  readonly key: string
  readonly name: string
}

/** 候选被任命者。**只给本盟成员**（B13 §一：被任命者必须属于某个已入籍的联盟）。 */
export function buildAppointRows(members: readonly { id: string; name: string }[]): AppointRowView[] {
  return members.slice(0, 8).map(member => ({ key: member.id, name: member.name }))
}

/** 任命那一块的一行（成员 + 官职键的组合在表现层做，这里只给名单）。 */
export type NationAppointRow = AppointRowView

/** S2 三块打成一份，交给表现层一次画完（与 S1 的 `nation` 回调同一个落点，不开第二条通道）。 */
export interface NationSectionsView {
  readonly tech: NationTechSection
  readonly diplomacy: {
    readonly rows: readonly DiplomacyRowView[]
    /** 还没打过一次交道时的说明（`rows` 为空时必须有它，否则是一片空白）。 */
    readonly emptyText: string | null
    /** 候选目标国（来自国家榜；`key` 是 nationId，只用于发请求，永不上屏）。 */
    readonly targets: readonly { key: string; name: string }[]
    readonly options: readonly { key: string; label: string; note: string }[]
  }
  readonly appoint: {
    readonly rows: readonly NationAppointRow[]
    readonly offices: readonly { key: string; label: string }[]
    readonly notice: string | null
  }
}

/**
 * 组装 S2 三块。
 *
 * @param tech        `GET /nation/tech` 的结果（没拉过为 null）
 * @param relations   变更之后回的那张关系表（**null = 还没打过交道**，不是"没有关系"）
 * @param targets     候选目标国（国家榜）
 * @param members     可被任命的本盟成员
 */
export function buildNationSections(tech: NationTechListView | null,
  relations: readonly NationRelationView[] | null,
  targets: readonly { nationId: string; name: string }[],
  members: readonly { id: string; name: string }[]): NationSectionsView {
  const rows = buildDiplomacyRows(relations)
  const appointRows = buildAppointRows(members)
  return {
    tech: buildNationTechSection(tech),
    diplomacy: {
      rows,
      emptyText: rows.length === 0
        ? '还没有打过一次交道：选一个国家，再选一种关系，就能把这一方的态度记下来'
        : null,
      targets: targets.slice(0, 8).map(t => ({ key: t.nationId, name: t.name })),
      options: DIPLOMACY_OPTIONS,
    },
    appoint: {
      rows: appointRows,
      offices: APPOINTABLE_OFFICES,
      notice: appointRows.length === 0 ? '本盟还没有别的成员可任命' : null,
    },
  }
}

/** 关系变更之后给玩家看的那一句。**刻意不说"条约已生效"**：C21 裁决下要双方都记着才成立。 */
export function diplomacyNotice(targetName: string, relation: string): string {
  return `已把与 ${targetName} 的关系记为「${diplomacyLabel(relation)}」`
    + '（盟约与附庸要对方也记着同一句才双向生效）'
}
