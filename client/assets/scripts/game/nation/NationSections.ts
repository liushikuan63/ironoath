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
import type { NationRelationView, WarStatusResp, WarNationScoreView } from '../../net/generated/NationProtocol'
import type { NationTechListView, NationTechView } from '../../net/generated/NationTechProtocol'
import { effectAttrLabel, schoolLabel } from '../tech/TechPanel'
import { amountText, permissionGateOf } from './NationPanel'
import type { NationGate } from './NationPanel'
import type { PolicyPanel } from './NationPolicyPanel'

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
    /** 能不能改关系（权限位 `MANAGE_DIPLOMACY`）。灰的时候 `gate` 里带着为什么。 */
    readonly gate: NationGate
  }
  readonly appoint: {
    readonly rows: readonly NationAppointRow[]
    readonly offices: readonly { key: string; label: string }[]
    readonly notice: string | null
    /** 能不能任命（权限位 `APPOINT_OFFICE`）。 */
    readonly gate: NationGate
  }
  /**
   * 国策那一页（B13 §4）。**与前三块刻意不同源**：`buildPolicyPanel` 在
   * {@code game/nation/NationPolicyPanel} 里，形状由协议定而不是这里拼 ——
   * 原因是这一页要下发的字段太多（两份公示名单、槽位说明、两个三态门禁），
   * 在这里拼就等于让这一页有第二个家的风险。
   */
  readonly policy: PolicyPanel | null
  /**
   * 国战那一页（B13 §一 §7 / V18 的客户端承接）。**与前三块不同源**：它读的是
   * `GET /nation/war`（`WarStatusResp`），是**全服级**的一条状态，不是本国的账 ——
   * 放在这里是因为入口在国家面板里，而"哪个接口供数"与"画在哪一页"是两件事。
   */
  readonly war: WarSection
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
  members: readonly { id: string; name: string }[],
  permissions: readonly string[] | null = null,
  permissionsLoaded = false,
  policy: PolicyPanel | null = null,
  war: WarStatusResp | null = null): NationSectionsView {
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
      // 关系变更的权限位（`MANAGE_DIPLOMACY`：国王与外交官档）。**不许自己按官职名判** ——
      // "谁是外交官"由 role_permission 表说了算，客户端抄一份就会在表改了的那天骗人
      gate: permissionGateOf('MANAGE_DIPLOMACY', '变更外交', permissions, permissionsLoaded),
    },
    appoint: {
      rows: appointRows,
      offices: APPOINTABLE_OFFICES,
      notice: appointRows.length === 0 ? '本盟还没有别的成员可任命' : null,
      gate: permissionGateOf('APPOINT_OFFICE', '任命官职', permissions, permissionsLoaded),
    },
    // **默认 null**（不是空面板）：这一页没拉过时面板要说「这一次没拉到」，
    // 画一片空白会让玩家以为这个国家没有国策可议。
    policy,
    war: buildWarSection(war),
  }
}

// ---------- 国战那一页（B13 §一 §7；V18 的客户端承接） ----------

/**
 * 国战阶段 → 玩家语言。**switch + 未知即抛**（与榜那边 `isPersonalBoard` 同一种 fail-closed 形状）：
 * 加一段阶段时编译期不报错，静默落到某一句上的代价是面板说了一句不相干的话。
 */
export function warPhaseLabel(phase: 'PREPARATION' | 'SIEGE' | 'SETTLED'): string {
  switch (phase) {
    case 'PREPARATION':
      return '筹备期：各盟在争周边的关卡，拿到关卡才有进攻资格'
    case 'SIEGE':
      return '王城战进行中'
    case 'SETTLED':
      return '这一场已经结束（积分定格）'
    default:
      throw new Error(`不认识的国战阶段：${String(phase)}`)
  }
}

/** 王城归属那一句：无人占领时说"无人占领"，**不拿空串当"没人"**。 */
export function capitalTextOf(name: string | null): string {
  return name === null || name === '' ? '王城：目前没有人占着' : `王城：${name}占着`
}

/** 国战那一页的展示数据（引擎无关，可脱离 Cocos 跑单测）。 */
export interface WarSection {
  readonly hasWar: boolean
  /** 有仗时的一行状态；无仗时为 null（`emptyText` 顶上）。 */
  readonly headline: string | null
  readonly phaseText: string | null
  readonly remainingText: string | null
  readonly capitalText: string | null
  readonly rows: readonly WarSideRowView[]
  readonly goalText: string | null
  readonly fatigueText: string | null
  readonly emptyText: string | null
}

/** 一个参战方一行。**`key` 是 nationId，只用于发请求，永不上屏**（B13 红线）。 */
export interface WarSideRowView {
  readonly key: string
  readonly name: string
  readonly rankText: string
  readonly scoreText: string
  readonly gatesText: string
  readonly qualifiedText: string | null
}

/**
 * 国战状态 → 一页视图。
 *
 * <p><b>三态各自说清，别混成一态</b>：`null`（这一次没拉到 —— 网络/登录问题，该说"没读到"）、
 * `hasWar=false`（拉到了，全服现在确实没有仗）、有仗。把前两者合成一句"暂时没有国战"，
 * 玩家在断网时会以为国战系统是关着的。
 *
 * <p><b>剩余时间只用服务端给的 `remainingSec`</b>（铁律 5：不引本机时钟 —— 否则改手机时间
 * 就能让面板显示任意剩余）。非 SIEGE 阶段它恒为 0，所以那一行只在 SIEGE 画。
 *
 * <p><b>国名缺失给回退语</b>：`nationName` 在协议里不是 required（国家解散后仍留着参战行），
 * 印 `nationId` 是红线（内部 id 不进玩家面）。
 */
export function buildWarSection(resp: WarStatusResp | null): WarSection {
  if (resp === null) {
    return {
      hasWar: false,
      headline: null,
      phaseText: null,
      remainingText: null,
      capitalText: null,
      rows: [],
      goalText: null,
      fatigueText: null,
      emptyText: '这一次没读到国战状态：重进这一页再试',
    }
  }
  if (!resp.hasWar) {
    return {
      hasWar: false,
      headline: null,
      phaseText: null,
      remainingText: null,
      capitalText: null,
      rows: [],
      // 全服进度与我的疲劳**没有仗时也要画**：B13 §7 明写"不打国战的人的贡献也算"
      // （打野、打关卡都进 totalKills），这一行正是那句话在界面上的落点；
      // 疲劳则是"我还能不能出兵"的读数。两样都与"有没有仗"无关。
      goalText: `全服击杀 ${amountText(resp.totalKills)} / ${amountText(resp.serverGoalKills)}`
        + (resp.serverGoalReached ? '（全服目标已达成）' : ''),
      fatigueText: `我的疲劳 ${amountText(resp.myFatigue)} / ${amountText(resp.fatigueMax)}`,
      emptyText: '现在没有正在打的国战。全服的击杀进度会累计在上面那一行，'
        + '下一场由国王在「外交」那一页挑一个目标提出来。',
    }
  }
  const ranked = rankWarSides(resp.scores)
  return {
    hasWar: true,
    headline: `本场开战于：${new Date(resp.startedAt ?? 0).toISOString().slice(0, 10)}（UTC）`,
    phaseText: resp.phase === null ? null : warPhaseLabel(resp.phase),
    remainingText: resp.phase === 'SIEGE' ? `王城战剩余：${durationText(resp.remainingSec)}` : null,
    capitalText: capitalTextOf(resp.capitalHolderName ?? null),
    rows: ranked.map((entry): WarSideRowView => ({
      key: entry.row.nationId,
      name: entry.row.nationName === null || entry.row.nationName === ''
        ? '未知国家' : entry.row.nationName,
      rankText: `第 ${entry.rank} 名`,
      scoreText: amountText(entry.row.totalScore),
      gatesText: `关卡 ${entry.row.gatesHeld} / 共 ${resp.gateCount}`,
      qualifiedText: entry.row.attackQualified ? '已取得进攻资格' : null,
    })),
    goalText: `全服击杀 ${amountText(resp.totalKills)} / ${amountText(resp.serverGoalKills)}`
      + (resp.serverGoalReached ? '（全服目标已达成）' : ''),
    fatigueText: `我的疲劳 ${amountText(resp.myFatigue)} / ${amountText(resp.fatigueMax)}`
      + (resp.canMarch ? '' : '（已到顶，这一轮出不了兵）'),
    emptyText: null,
  }
}

/** 名次按总分降序；平分同名次（与内核"平分不给胜者"同一口径：不硬挑）。 */
function rankWarSides(sides: readonly WarNationScoreView[]):
  readonly { row: WarNationScoreView; rank: number }[] {
  const sorted = sides.map((row, index) => ({ row, index }))
    .sort((a, b) => (b.row.totalScore - a.row.totalScore) || (a.index - b.index))
  const out: { row: WarNationScoreView; rank: number }[] = []
  let lastScore: number | null = null
  let lastRank = 0
  sorted.forEach((entry, position) => {
    const rank = entry.row.totalScore === lastScore ? lastRank : position + 1
    lastScore = entry.row.totalScore
    lastRank = rank
    out.push({ row: entry.row, rank })
  })
  return out
}

/** 秒 → 「x 小时 y 分」。**不显示秒**：一场仗以小时计，秒位只会让面板每帧都不一样。 */
export function durationText(seconds: number): string {
  const safe = Math.max(0, Math.floor(seconds))
  const hours = Math.floor(safe / 3600)
  const minutes = Math.floor((safe % 3600) / 60)
  return hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分`
}

/** 关系变更之后给玩家看的那一句。**刻意不说"条约已生效"**：C21 裁决下要双方都记着才成立。 */export function diplomacyNotice(targetName: string, relation: string): string {
  return `已把与 ${targetName} 的关系记为「${diplomacyLabel(relation)}」`
    + '（盟约与附庸要对方也记着同一句才双向生效）'
}
