/**
 * 职责：出征的**编成**与「上次出征」的展示数据组装（B25-S1，裁决②(a) 与 ④(b)）。引擎无关，可脱离 Cocos 跑单测。
 * 依赖：生成的协议类型（`ArmyProtocol` / `WorldProtocol`）。
 *
 * <p><b>本模块不做任何"能不能打"的判定</b>（B08 禁止项：客户端不做战力校验）：圈层、目标合法性、
 * 负载、体力、武将是否可用——全部由服务端在 `MarchAppService.send` 里判。这里只做两件**纯展示**的事：
 * ① 把服务端下发的可用兵力摊成"可勾选的编成"（数量夹在 [0, 可用] 之内）；
 * ② 记住**最后一次成功**出征的业务参数，供「再次出征」重发。
 *
 * <p><b>裁决④(b) 的落点：不自动勾选全军</b>。默认全军看起来省事，但打野时倾巢而出会把家底一次性押在一场
 * 不划算的仗上——那不是"减负"，是给玩家挖坑。所以 `picked` 由调用方给，本模块只负责夹取与汇总。
 *
 * <p><b>裁决②(a) 的落点：记住的是"最后一次成功"的参数，不是"最后一次发出"的</b>。
 * 失败的请求（被圈层拒、目标已失效、网络断）记下来的话，「再次出征」会重发一支本来就发不出去的队伍，
 * 第二次失败对玩家是纯噪声。
 *
 * <p><b>过时参数不静默改小</b>：上一次带了 800 兵，今天只剩 300——重发时必须**明确说凑不齐**，
 * 而不是偷偷按 300 发出去。后者在玩家眼里是"我点的是同一支队伍，怎么打输了"，而原因（兵力不够）他永远看不到。
 */

import type { ArmyListResp, UnitView } from '../../net/generated/ArmyProtocol'
import type { MarchAction, MarchReq, MarchUnit } from '../../net/generated/WorldProtocol'

/** 编成里的一行（一个兵种）。 */
export interface ComposeOption {
  readonly unitId: string
  readonly name: string
  readonly tier: number
  /** 可出征上限 = 服务端下发的可用兵力（不含训练中、不含伤兵） */
  readonly available: number
  /** 本次勾选的数量，恒在 [0, available] 内 */
  readonly selected: number
  readonly unlocked: boolean
  /** 未解锁时的结构化提示（服务端下发），已解锁为 null */
  readonly unlockHint: string | null
}

/** 编成面板的完整展示数据。 */
export interface ComposeView {
  readonly options: readonly ComposeOption[]
  /** 勾选总数 */
  readonly totalText: string
  /** 至少带了一个兵 */
  readonly hasTroops: boolean
  /** 能不能提交；不能时为 false */
  readonly canSubmit: boolean
  /** 不能提交的原因（人话）；能提交时为 null。**必须给原因**：一个灰按钮会让玩家以为坏了 */
  readonly blockedReason: string | null
}

/** 一次出征的业务参数。<b>刻意不含 requestId</b>：键属于"这一次提交"，不属于"这支队伍"。 */
export interface MarchSpec {
  readonly toX: number
  readonly toY: number
  readonly units: readonly MarchUnit[]
  readonly heroes: readonly string[]
  readonly action: MarchAction
}

/**
 * 把服务端下发的军队摊成可勾选的编成。
 *
 * @param picked 各兵种勾选数量；缺省视为 0（**不自动勾选**，见类注释的裁决④(b)）
 */
export function buildCompose(army: ArmyListResp, picked: Readonly<Record<string, number>>): ComposeView {
  if (army === null || army === undefined) {
    throw new Error('army 不得为空')
  }
  const options: ComposeOption[] = army.units.map((unit: UnitView) => {
    const wanted = picked[unit.unitId] ?? 0
    const available = Math.max(0, unit.count)
    return {
      unitId: unit.unitId,
      name: unit.name,
      tier: unit.tier,
      available,
      // 未解锁的兵种即使有存量也带不出去（服务端会拒），所以夹到 0
      selected: unit.unlocked ? clamp(wanted, 0, available) : 0,
      unlocked: unit.unlocked,
      unlockHint: unit.unlockHint,
    }
  })
  const total = options.reduce((sum, option) => sum + option.selected, 0)
  const hasTroops = total > 0
  return {
    options,
    totalText: `${total}`,
    hasTroops,
    canSubmit: hasTroops,
    blockedReason: hasTroops
      ? null
      // 玩家可见的一句，不提"服务端"（那道判定在服务端，但那是实现细节）
      : '至少带一个兵才能出征',
  }
}

/** 勾选/取消某个兵种。返回新的勾选表（本模块不改入参）。 */
export function setPick(army: ArmyListResp, picked: Readonly<Record<string, number>>,
                        unitId: string, next: number): Record<string, number> {
  const unit = army.units.find(candidate => candidate.unitId === unitId)
  if (unit === undefined) {
    throw new Error(`军队里没有这个兵种：${unitId}`)
  }
  const bound = unit.unlocked ? Math.max(0, unit.count) : 0
  return { ...picked, [unitId]: clamp(Math.floor(next), 0, bound) }
}

/**
 * 由编成视图拼出 `MarchReq.units`：**只带选中的兵种**（0 的整行不出现）。
 *
 * <p>带 0 的行不是"等价写法"：服务端的兵种校验里有 `count <= 0` 那条拒绝，
 * 多带一行 0 会让一次正常的出征被回一句「兵种数量必须为正」。
 */
export function marchUnitsOf(view: ComposeView): MarchUnit[] {
  return view.options
    .filter(option => option.selected > 0)
    .map(option => ({ unitId: option.unitId, count: option.selected }))
}

/** 记住一次**成功**的出征（失败的不记：记了就是让「再次出征」重发一支发不出去的队伍）。 */
export function rememberMarch(previous: MarchSpec | null, spec: MarchSpec): MarchSpec {
  if (spec === null || spec === undefined) {
    throw new Error('spec 不得为空')
  }
  if (spec.units.length === 0) {
    throw new Error('一次成功的出征不可能不带兵：这个 spec 记错了')
  }
  return previous === null ? spec : spec
}

/**
 * 「再次出征」的提交体：**业务字段照搬、requestId 由调用方新生成**。
 *
 * <p>复用旧键的后果不是"重复提交被去重"，而是 `REQUEST_DUPLICATED`——那键的语义是"用过了"，
 * 不是"幂等回放"（B25 服务端红线用例记过这条）。所以键必须新的，而队伍参数必须一模一样。
 */
export function repeatRequestOf(spec: MarchSpec, requestId: string): MarchReq {
  if (requestId === null || requestId.trim().length === 0) {
    throw new Error('requestId 不得为空：服务端会按它判幂等')
  }
  return {
    requestId,
    toX: spec.toX,
    toY: spec.toY,
    units: spec.units.map(unit => ({ unitId: unit.unitId, count: unit.count })),
    heroes: [...spec.heroes],
    action: spec.action,
  }
}

/**
 * 「再次出征」此刻能不能发；不能时给出**人话原因**（而不是悄悄改小队伍）。
 *
 * @param army 当前军队（服务端最近一次下发的），用来判断"上次那支队伍现在还凑不齐吗"
 */
export function repeatBlockedReason(spec: MarchSpec | null, army: ArmyListResp | null): string | null {
  if (spec === null) {
    return '还没有成功出征过，没有可以重复的队伍'
  }
  if (army === null) {
    return '军队信息还没拉到，先等一下'
  }
  for (const unit of spec.units) {
    const current = army.units.find(candidate => candidate.unitId === unit.unitId)
    if (current === undefined) {
      return `上次带的兵种不在了：${unit.unitId}（换过编制？）`
    }
    if (!current.unlocked) {
      return `上次带的 ${current.name} 现在不可用（${current.unlockHint ?? '未解锁'}）`
    }
    if (current.count < unit.count) {
      // 关键：**说清差多少**，而不是按当前有的数量发出去
      return `上次带了 ${unit.count} 个 ${current.name}，现在只剩 ${current.count} 个 —— 凑不齐就不是同一支队伍了`
    }
  }
  return null
}

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) {
    return min
  }
  return Math.min(Math.max(value, min), max)
}
