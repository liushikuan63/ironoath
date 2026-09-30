/**
 * 职责：国家面板（B13 · V13-S1）的数据组装 —— 「我现在有没有国家、能做什么、国库这本账怎么样」。
 * 依赖：生成的协议类型 + `game/ui/ElapsedText`（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p>**这一层不做任何业务判定**：建国前置（主城 16 级 / 开服 D14 / 在联盟中）、入籍冷却、
 * 名额、支出限额全在服务端。客户端只在**服务端已经下发的事实**上做展示判定 ——
 * 而这些事实只有三样：`myOffice`（我担任的官职）、`kingId`（谁是国王）、`allianceCount/memberCap`。
 *
 * <p><b>三处不许上屏裸值</b>（红线：内部 id / 枚举原文不进玩家面）：
 * ① 官职 —— 契约里 `myOffice` 刻意用枚举名字符串，注释写明「客户端要显示的是中文名，
 *    而那份文案在客户端的本地化表里」。所以这里有一张 {@link OFFICE_LABELS}，查不到给「未知官职」。
 * ② 国库流水的 `operatorId` / `counterparty` —— 后者是 `player:<id>` / `sink:<用途>` 两种形态，
 *    前者是玩家 id 或 `system`。全部经 {@link operatorLabel} / {@link payeeLabel} 换成词。
 * ③ 金额 —— 一律最小单位整数（铁律：不用浮点），只在本层做展示层的千分位。
 *
 * <p><b>「不在任何国家」是正常起点而不是错误</b>：服务端回 13000 `NATION_NOT_FOUND`，
 * 调用方把它折成 `nation: null` 传进来。所以 {@link buildNationPanel} 对两种形态都有完整视图，
 * 断言也能分别验到。
 */

import type {
  NationTreasuryResp, NationTreasurySpendResp, NationView, TreasuryLogView,
} from '../../net/generated/NationProtocol'
import { elapsedText } from '../ui/ElapsedText'
import type { NationSectionsView } from './NationSections'

/**
 * 国家面板的页签（S2 起）。
 *
 * <p>**TREASURY 在前**：国库是这一屏最该先看见的东西（余额与流水），
 * 其余四块都是"要做事才点进去"的动作页。
 *
 * <p><b>POLICY 放在任命之后（最后一位）</b>：国策是唯一一个要**等**的动作页 ——
 * 提案段要等、投票段要等、还有生效段。与其放前面占掉「国库」之后那个位置，
 * 不如放在最后，让"要做事"的那几页聚在一起。
 */
export type NationTabKey = 'TREASURY' | 'TECH' | 'DIPLO' | 'OFFICE' | 'POLICY'

/** 页签的中文名与它各自的键。表驱动是为了表现层只有一处 switch。 */
export const NATION_TABS: readonly { key: NationTabKey; label: string }[] = [
  { key: 'TREASURY', label: '国库' },
  { key: 'TECH', label: '国家科技' },
  { key: 'DIPLO', label: '外交' },
  { key: 'OFFICE', label: '任命' },
  { key: 'POLICY', label: '国策' },
]

/**
 * 服务端用来表示「这条流水是系统动作」的 operatorId（契约里写明周税入账就是它）。
 * 印出来会是 `system`，所以必须在展示层换成「系统」。
 */
export const SYSTEM_OPERATOR = 'system'

/** 查不到名字时的回退语。**不是**把 id 印出来。 */
export const UNKNOWN_MEMBER = '未知成员'
export const UNKNOWN_OPERATOR = '未知操作人'
export const UNKNOWN_SINK = '其他用途'
export const UNKNOWN_OFFICE = '未知官职'

/**
 * 官职枚举名 → 中文。
 *
 * <p>**这份表在客户端是契约要求的**（`NationView.myOffice` 的注释：服务端下发枚举名，
 * 客户端据此查本地化表），不是"客户端抄了配置表"—— 服务端配的是席位与权限，
 * 这里配的是中文词；改一次文案只动这一份，不必动服务端。
 *
 * <p>键必须与 `NationOffice` 逐一对应。少一个键不会编译失败，所以 {@link officeLabel}
 * 对查不到的一律给「未知官职」—— 那正是枚举漂移的症状，不该印 `GENERAL` 给玩家看。
 */
export const OFFICE_LABELS: Readonly<Record<string, string>> = {
  KING: '国王',
  PRIME_MINISTER: '首相',
  GENERAL: '大将军',
  MINISTER: '内政官',
  DIPLOMAT: '外交官',
  REPRESENTATIVE: '议员',
}

/** 消耗性用途枚举 → 中文。名字进流水日志是为了与文档对照，玩家面要的是词。 */
export const SINK_LABELS: Readonly<Record<string, string>> = {
  NATIONAL_TECH: '国家科技',
  WAR_BOOST: '国战增益',
}

/**
 * **不带前缀**的对手方 token → 中文。
 *
 * <p>协议的注释说"方向由 counterparty 表达"，而实际有三种形态：`player:<id>`（支给谁）、
 * `sink:<用途>`（核销到哪个子系统）、以及**裸 token**（系统自己产生的那几笔）。
 * 裸 token 这一形态是**真链路回读屏才发现的**：周税入账那一行原先落进回退语，
 * 屏上显示成「其他用途 · 10,000」—— 而它明明是一笔**入账**，"用途"这个词会让玩家以为钱花掉了。
 * 两轮夹具探针都没照出它，因为夹具的流水里没有周税那一笔（真后端才有）。
 *
 * <p>取值来自服务端领域层的字面量（`Nation`：`weekly_tax` / `disband_writeoff`）。
 * 查不到时给 {@link UNKNOWN_PAYEE}，**绝不把 token 原样印出去**。
 */
export const PLAIN_PAYEE_LABELS: Readonly<Record<string, string>> = {
  weekly_tax: '成员联盟周税',
  disband_writeoff: '亡国核销',
  war_loot: '国战战利品',
}

/** 裸 token 查不到时的回退语。**与 {@link UNKNOWN_SINK} 分开**：一个说"用途不明"，一个说"这笔是什么不明"。 */
export const UNKNOWN_PAYEE = '其他'

/** 一屏排得下几条流水。**版式常量，不是业务口径**（条数上限由服务端在领域层截断）。 */
export const TREASURY_LOG_ROWS = 6

/**
 * 退国后的冷却提示。
 *
 * <p>协议里那一条明确禁止的是「客户端拿一个时长自己加」（铁律 5：展示与判定两侧各算一遍时间，
 * 本机钟一偏就会出现"显示能加入、服务端却拒绝"）。所以这里**只用两个同源的服务端时刻相减**，
 * 不引本机时钟，也不用任何本地配置里的冷却时长。
 *
 * <p>已经过去（差值 ≤ 0）时说「现在就可以再申请」：那是服务端把冷却算完了的形态，
 * 说一句"还差 0 小时"会让玩家以为还要等。
 */
export function cooldownText(cooldownUntil: number, serverNow: number): string {
  const remain = cooldownUntil - serverNow
  if (!Number.isFinite(remain) || remain <= 0) {
    return '现在就可以再次入籍'
  }
  const hours = Math.ceil(remain / 3_600_000)
  if (hours < 24) {
    return `约 ${hours} 小时后可以再次入籍`
  }
  return `约 ${Math.ceil(hours / 24)} 天后可以再次入籍`
}

/** 官职枚举名 → 中文；null 表示「我在这个国家里没有官职」。 */
export function officeLabel(office: string | null | undefined): string {  if (office === null || office === undefined || office === '') {
    return '无官职'
  }
  return OFFICE_LABELS[office] ?? UNKNOWN_OFFICE
}

/** 谁做的这一笔。`system` 说「系统」，玩家 id 靠名单换成名字，换不到给「未知操作人」。 */
export function operatorLabel(operatorId: string, names: ReadonlyMap<string, string>): string {
  if (operatorId === SYSTEM_OPERATOR) {
    return '系统'
  }
  return names.get(operatorId) ?? UNKNOWN_OPERATOR
}

/**
 * 支给谁（`player:<id>`）或去向哪（`sink:<用途>`）。
 *
 * <p>两种形态刻意合成一个字段（契约里说明理由：把方向拆成两列会让「把金额加总」得出一个说不清的结论），
 * 所以这里要按前缀分派。**两种形态都换不成词时给回退语，绝不把 `player:xxx` 原样印出去。**
 */
export function payeeLabel(counterparty: string, names: ReadonlyMap<string, string>): string {
  if (counterparty.startsWith('player:')) {
    return names.get(counterparty.slice('player:'.length)) ?? UNKNOWN_MEMBER
  }
  if (counterparty.startsWith('sink:')) {
    return SINK_LABELS[counterparty.slice('sink:'.length)] ?? UNKNOWN_SINK
  }
  if (counterparty === SYSTEM_OPERATOR) {
    return '系统'
  }
  // 裸 token（周税 / 亡国核销…）：**必须有自己的词表**，否则一笔入账会显示成「其他用途」
  return PLAIN_PAYEE_LABELS[counterparty] ?? UNKNOWN_PAYEE
}

/**
 * 最小单位整数 → 展示文本（千分位）。
 *
 * <p>**只在展示层做**：协议里的金额恒是最小单位整数（B01 的定点约定），客户端不许乘也不许四舍五入。
 * 非有限数（NaN / Infinity）给 `0` 而不是 `NaN` 上屏 —— 那会让国库余额显示成 "NaN"，
 * 而玩家看不出那是"读坏了"还是"真没有"。
 */
export function amountText(value: number): string {
  if (!Number.isFinite(value)) {
    return '0'
  }
  const negative = value < 0
  const digits = Math.abs(Math.trunc(value)).toString()
  let out = ''
  for (let i = 0; i < digits.length; i += 1) {
    if (i > 0 && (digits.length - i) % 3 === 0) {
      out += ','
    }
    out += digits[i]
  }
  return negative ? `-${out}` : out
}

/** 面板上的一行（`text` 已含字段名，表现层只画）。 */
export interface NationSummaryLine {
  readonly key: string
  readonly text: string
}

/** 国库流水的一行。**四样缺一不可**（谁 / 何时 / 支给谁 / 多少 —— B13 §3 验收 5）。 */
export interface TreasuryRowView {
  readonly key: string
  /** 「支给谁 · 金额」。 */
  readonly headText: string
  /** 「多久以前 · 谁做的 · 用途」。 */
  readonly detailText: string
  /** 「余额 X」：带上它，流水能自证连贯（相邻两行的余额差就该等于下一行的金额）。 */
  readonly balanceText: string
}

/** 可加入的一个国家。 */
export interface NationCandidateView {
  readonly key: string
  readonly name: string
  readonly actionText: string
  readonly enabled: boolean
  readonly reason: string | null
}

/** 一颗键：亮不亮与「为什么灰」都在这里说清（表现层不自己判）。 */
export interface NationActionView {
  readonly text: string
  readonly enabled: boolean
  readonly reason: string | null
}

/** 国库那一块。`null` = 拿不到（不在任何国家，或这一次没拉到）。 */
export interface NationTreasuryView {
  readonly balanceText: string
  readonly capText: string
  readonly logs: readonly TreasuryRowView[]
  readonly emptyText: string | null
  /** 流水条数被这一屏截断了多少条（0 = 全在屏上）。 */
  readonly hiddenCount: number
}

export interface NationPanelView {
  readonly mode: 'NONE' | 'MEMBER'
  /** 当前页签（S2 起）。无国家时恒为 `'TREASURY'` —— 没有国家就没有国库之外的东西。 */
  readonly tab: NationTabKey
  /** S2 三块（科技 / 外交 / 任命）。`null` = 还没拉过（打开科技页签才发那一枪）。 */
  readonly sections: NationSectionsView | null
  readonly title: string
  /** 一句话说清"我现在是什么处境"。 */
  readonly headline: string
  readonly summary: readonly NationSummaryLine[]
  readonly treasury: NationTreasuryView | null
  readonly candidates: readonly NationCandidateView[]
  readonly candidatesNotice: string | null
  readonly found: NationActionView
  readonly leave: NationActionView
  readonly disband: NationActionView
  readonly spend: NationActionView
  /** 上一次操作的结果（成功一句 / 服务端拒绝的理由），没有则 null。 */
  readonly notice: string | null
  /**
   * notice 的语气。**成功与失败必须分开**：用同一个红字去显示"研究完成"，
   * 玩家读到的是"出错了"—— 而这一屏同时承载公共资产的操作，误读的代价很高。
   * 默认 `'warn'`（失败），编排层要在成功时显式传 `'ok'`。
   */
  readonly noticeTone: 'ok' | 'warn'
}

/** 可加入国家的一行来源。id 与名字都由服务端下发（`GET /rank/list?type=NATION`）。 */
export interface NationCandidate {
  readonly nationId: string
  readonly name: string
}

export interface NationPanelInput {
  /** `GET /nation` 的结果。不在任何国家时为 null（错误码 13000，不是网络失败）。 */
  readonly nation: NationView | null
  /** `GET /nation/treasury` 的结果。不在任何国家 / 这一次没拉到时为 null。 */
  readonly treasury: NationTreasuryResp | null
  /** 可加入的国家（来自国家榜；`nationId` 只用于发请求，永不上屏）。 */
  readonly candidates: readonly NationCandidate[]
  /** 我这个号的玩家 id（登录时下发）。用于判「我是不是国王」。 */
  readonly playerId: string
  /** 联盟成员名单：流水里的操作人与收款人靠它换成名字（id → 昵称）。 */
  readonly memberNames: ReadonlyMap<string, string>
  readonly notice: string | null
  /** notice 的语气；不给按 `'warn'`（失败）算。 */
  readonly noticeTone?: 'ok' | 'warn'
  /**
   * `GET /social/permissions?scope=NATION` 的权限位（`role_permission.permission`）——
   * **灰键的权威来源**（V13-d：能做什么由服务端权限位决定）。
   */
  readonly permissions?: readonly string[] | null
  /** 那一份权限位读到了没有。**读不到时不给"你不行"，也不放行**（见 `permissionGateOf`）。 */
  readonly permissionsLoaded?: boolean
  /** 当前页签（S2）。无国家时忽略。 */
  readonly tab?: NationTabKey
  /** S2 三块；没拉过时为 null（面板据它说"这一次没读到"，不是画一片空白）。 */
  readonly sections?: NationSectionsView | null
}

const OFF_ACTION: NationActionView = { text: '—', enabled: false, reason: '你还不在任何国家里' }
const IN_ACTION: NationActionView = { text: '—', enabled: false, reason: '你已经在一个国家里' }

/**
 * 权限位 → 「这颗键亮不亮、为什么灰」。
 *
 * <p>三条口径（与 `game/social/PermissionGates.gate` 同一套，刻意不另立一套）：
 * ① 权限位里有这一位 ⇒ 亮；
 * ② 读到了、但没有这一位 ⇒ 灰，理由是**身份结论**（"你当前的职位不能动国库"）；
 * ③ **没读到**（请求失败 / 还没回来）⇒ 灰，理由是"权限还没读到"。
 *
 * <p>③ 不能写成②：把一次读失败说成"你不行"，玩家会去申请升职 ——
 * 而真正该做的只是重进这一页。这条纪律在社交页那边已经立过一次。
 */
/** 权限位的判定结果（`text` 由调用方补：同一份判定服务三颗不同文案的键）。 */
export interface NationGate {
  readonly enabled: boolean
  readonly reason: string | null
}

/**
 * 权限位 → 「这颗键亮不亮、为什么灰」。
 *
 * <p>三条口径（与 `game/social/PermissionGates.gate` 同一套，刻意不另立一套）：
 * ① 权限位里有这一位 ⇒ 亮；
 * ② 读到了、但没有这一位 ⇒ 灰，理由是**身份结论**（"你当前的职位不能动国库"）；
 * ③ **没读到**（请求失败 / 还没回来）⇒ 灰，理由是"权限还没读到"。
 *
 * <p>③ 不能写成②：把一次读失败说成"你不行"，玩家会去申请升职 ——
 * 而真正该做的只是重进这一页。这条纪律在社交页那边已经立过一次。
 *
 * @param action 这一位管的是哪件事（"动国库" / "任命官职" / "变更外交"），拼进那句理由
 */
export function permissionGateOf(code: string, action: string,
                                permissions: readonly string[] | null | undefined,
                                loaded: boolean | undefined): NationGate {
  if (loaded !== true) {
    return { enabled: false, reason: '权限还没读到' }
  }
  if ((permissions ?? []).includes(code)) {
    return { enabled: true, reason: null }
  }
  return { enabled: false, reason: `你当前的职位不能${action}` }
}

/** 一条流水 → 一行。`serverNow` 必须与 `log.at` 同源（铁律 5：不引本机时钟）。 */
export function buildTreasuryRow(log: TreasuryLogView, names: ReadonlyMap<string, string>,
  serverNow: number, index: number): TreasuryRowView {
  return {
    key: `${log.at}-${index}`,
    headText: `${payeeLabel(log.counterparty, names)} · ${amountText(log.amount)}`,
    detailText: `${elapsedText(serverNow - log.at)} · ${operatorLabel(log.operatorId, names)} · ${log.reason}`,
    balanceText: `余额 ${amountText(log.balanceAfter)}`,
  }
}

/** 一笔刚发生的支出 → 一行（`NationTreasurySpendResp` 回整条流水，面板立刻显示它）。 */
export function buildSpendRow(resp: NationTreasurySpendResp, names: ReadonlyMap<string, string>): TreasuryRowView {
  const log = resp.log
  return {
    key: `spend-${log.at}`,
    headText: `${payeeLabel(resp.payee, names)} · ${amountText(resp.amount)}`,
    detailText: `${elapsedText(resp.serverNow - log.at)} · ${operatorLabel(log.operatorId, names)} · ${resp.reason}`,
    balanceText: `余额 ${amountText(resp.balance)}`,
  }
}

/**
 * 金额预设三档。**版式常量**（免得在手机上敲数字），不是业务口径 ——
 * 能不能花、花多少全由服务端判（13010 余额不足 / 13011 超本周限额），这三个数不参与任何判定。
 */
export const SPEND_AMOUNT_PRESETS: readonly number[] = [1_000, 10_000, 100_000]

/** 可选的收款人。`id` 只用于发请求，**永不上屏**（与 {@link NationCandidateView.key} 同一条纪律）。 */
export interface NationPayeeOption {
  readonly id: string
  readonly name: string
}

/** 落点类型（协议里的 `TreasuryPayeeType`）。 */
export type PayeeType = 'PLAYER' | 'SINK'

/** 支出表单当前这一份草稿（**纯展示态，不参与任何服务端判定**）。 */
export interface SpendDraft {
  readonly amount: number
  readonly reason: string
  readonly payeeType: PayeeType
  readonly payeeId: string | null
  readonly sink: string | null
}

/** 消耗性用途的两个可选项（枚举 → 中文，两边都由 {@link SINK_LABELS} 供值）。 */
export function spendSinkOptions(): readonly { key: string; label: string }[] {
  return Object.keys(SINK_LABELS).map(key => ({ key, label: SINK_LABELS[key] ?? UNKNOWN_SINK }))
}

/**
 * 这份草稿还差什么才能提交（null = 可以提交）。
 *
 * <p>只查**协议明写的前置**：`amount` 是正整数（领域层不允许半笔俸禄）、`reason` 非空
 * （「没有为什么的日志等于没有日志」）、`PLAYER` 必给 `payeeId`、`SINK` 必给 `sink`
 * （两者都给或都不给都会被服务端拒）。
 *
 * <p><b>不查国库余额、不查本周限额</b>：那两条只有服务端知道（13010/13011），
 * 客户端预判一遍就会出现"显示能花、服务端却拒绝"这种说不清的状态。
 */
export function spendDraftBlocker(draft: SpendDraft): string | null {
  if (!Number.isInteger(draft.amount) || draft.amount <= 0) {
    return '支出额要是一个大于 0 的整数'
  }
  if (draft.reason.trim() === '') {
    return '用途不能为空：国库流水要写明这一笔为什么花'
  }
  if (draft.payeeType === 'PLAYER') {
    return draft.payeeId === null || draft.payeeId === '' ? '选一个收这笔钱的人' : null
  }
  return draft.sink === null || draft.sink === '' ? '选一个消耗性用途' : null
}

function buildSummary(nation: NationView): NationSummaryLine[] {
  return [
    { key: 'name', text: `国名：${nation.name}` },
    { key: 'level', text: `国家等级：Lv${nation.level}` },
    {
      key: 'member',
      text: `成员联盟：${nation.allianceCount} / ${nation.memberCap}`,
    },
    {
      key: 'treasury',
      text: `国库：${amountText(nation.treasury)} / ${amountText(nation.treasuryCap)}`,
    },
    { key: 'capital', text: `都城：${nation.capitalX}, ${nation.capitalY}` },
    { key: 'office', text: `我的官职：${officeLabel(nation.myOffice)}` },
  ]
}

/**
 * 组装整块视图。
 *
 * <p>两种形态各有完整的键集合（无国家时 `leave/disband/spend` 仍然是「灰 + 写明为什么」），
 * 这样表现层不需要按 mode 分支去猜哪些键能画，单测也能分别验到两态。
 */
export function buildNationPanel(input: NationPanelInput): NationPanelView {
  const nation = input.nation
  if (nation === null) {
    const candidates = input.candidates.map((candidate): NationCandidateView => ({
      key: candidate.nationId,
      name: candidate.name,
      actionText: '申请加入',
      // 能不能加入全在服务端（冷却 / 名额 / 是否已属他国）。这一行不预判，
      // 被拒时把服务端那句 `detail` 放进 notice —— 预判等于客户端替服务端决定
      enabled: true,
      reason: null,
    }))
    return {
      mode: 'NONE',
      // 无国家时页签与 S2 三块都不存在：恒给默认值，而不是 null —— 表现层按 `mode` 分支，
      // 拿到一个 null 的 tab 会逼它写第二遍"这页没有页签条"
      tab: 'TREASURY',
      sections: null,
      title: '国家',
      headline: '你还没有国家。建国需要主城满级、开服满一定天数，而且你得在一个联盟里 —— 缺哪一条，界面上会写明。',
      summary: [],
      treasury: null,
      candidates,
      candidatesNotice: candidates.length === 0
        ? '本服还没有国家可以加入：你是第一个，先建一个。'
        : '下面是本服现有的国家（不是全部）。',
      found: { text: '创建国家', enabled: true, reason: null },
      leave: OFF_ACTION,
      disband: OFF_ACTION,
      spend: OFF_ACTION,
      notice: input.notice,
      noticeTone: input.noticeTone ?? 'warn',
    }
  }

  const names = input.memberNames
  const resp = input.treasury
  const all = resp?.logs ?? []
  const treasury: NationTreasuryView | null = resp === null || resp === undefined
    ? null
    : {
      balanceText: amountText(resp.balance),
      capText: amountText(nation.treasuryCap),
      logs: all.slice(0, TREASURY_LOG_ROWS).map((log, index) =>
        buildTreasuryRow(log, names, resp.serverNow, index)),
      emptyText: all.length === 0
        ? '国库还没有流水：每笔进出都会记在这里，谁花的、花给谁、为什么都写明'
        : null,
      hiddenCount: Math.max(0, all.length - TREASURY_LOG_ROWS),
    }

  const isKing = nation.kingId === input.playerId
  return {
    mode: 'MEMBER',
    tab: input.tab ?? 'TREASURY',
    sections: input.sections ?? null,
    title: nation.name,
    headline: `你在 ${nation.name} 的身份：${officeLabel(nation.myOffice)}。`
      + (isKing ? '你是这个国家的国王。' : ''),
    summary: buildSummary(nation),
    treasury,
    candidates: [],
    candidatesNotice: null,
    found: IN_ACTION,
    leave: { text: '退出国家', enabled: true, reason: null },
    // 解散的发起权来自「你是不是国王」，而 kingId 与我的 id 都是服务端下发的 ——
    // 客户端没有也不该有一张"谁能干什么"的本地权限表
    disband: {
      text: '解散国家',
      enabled: isKing,
      reason: isKing ? null : '只有国王能解散这个国家',
    },
    // 国库支出：**由权限位裁决**（`WITHDRAW_TREASURY`，V13-d 的口径）。
    // 从前这里只能用「你有没有官职」当代理 —— 那个代理是对的但不精确：
    // 谁是 OFFICER 由 role_permission 表说了算，表改了代理就骗人。
    // 表里没有"解散"这一位，所以解散仍然按 kingId（结构事实，不是权限），见上。
    spend: {
      text: '国库支出',
      ...permissionGateOf('WITHDRAW_TREASURY', '动国库', input.permissions, input.permissionsLoaded),
    },
    notice: input.notice,
    noticeTone: input.noticeTone ?? 'warn',
  }
}
