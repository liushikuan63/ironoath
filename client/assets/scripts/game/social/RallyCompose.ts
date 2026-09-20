/**
 * 职责：编成弹层里「出征 / 小队集结 / 联盟集结」三态的算术（B26 S13b）。纯逻辑，不碰引擎、不碰网络。
 * 依赖：`net/generated/SocialProtocol` 里的 `RallyPolicyResp` / `RallyPolicyView`。
 *
 * <p><b>为什么单独一份</b>：`/rally/alliance` 这个写口早就有、`GameApi.allianceRally` 也早就有，
 * 但整条链路上没有任何消费者（"有 API 无消费者"这一族的第十处）。补消费者时最容易犯的错是
 * 在客户端自己写人数上限与默认准备时长 —— 那两个数的真源是 `global.RALLY_*` 与服务端的夹取逻辑，
 * 抄一份就是留一个将来对不上的口径。所以本模块**只从读口给的 `RallyPolicyView` 取数**：
 * 上下界、默认值、能不能发起、不能发起的原因，一个字都不自己造。
 *
 * <p><b>步进值是这里唯一的客户端判断</b>，而且它是从服务端给的上下界推出来的
 * （`步长 = ceil((max-min)/10)`，最少 1）：一个"来回点几下就能调到位"的交互手感参数，
 * 不是游戏数值。写死 5 会在 min=max=1 的队上点出越界，写死 1 会让 120 分钟的窗口点 119 次。
 *
 * <p><b>切到不能发起的那一档是允许的</b>：被挡住时那一档要显示服务端那句原因，
 * 玩家点它才知道"我为什么不能"。把它从循环里摘掉，等于把原因一起藏了。
 */

import type { RallyPolicyResp, RallyPolicyView } from '../../net/generated/SocialProtocol'

/** 编成弹层的命令种类。MARCH = 只派自己这一队，另两档都要等人。 */
export type RallyKind = 'MARCH' | 'SQUAD_RALLY' | 'ALLIANCE_RALLY'

/** 面板上一格可调项：值、界、步长。界与默认值来自服务端，步长由界推出来。 */
export interface RallyControl {
  readonly value: number
  readonly min: number
  readonly max: number
  readonly step: number
}

/** 当前这一档的集结参数。MARCH 没有参数，所以整块是 null。 */
export interface RallyParams {
  readonly members: RallyControl
  readonly prepare: RallyControl
}

/** 三态循环的下一档。三档都在循环里，被挡住的也进 —— 理由见文件头。 */
export function nextKind(kind: RallyKind): RallyKind {
  if (kind === 'MARCH') {
    return 'SQUAD_RALLY'
  }
  return kind === 'SQUAD_RALLY' ? 'ALLIANCE_RALLY' : 'MARCH'
}

/** 这一档对应政策读口里的哪一份。MARCH 不需要政策，所以拿不到也算不出界。 */
export function policyFor(kind: RallyKind, resp: RallyPolicyResp | null): RallyPolicyView | null {
  if (kind === 'MARCH' || resp === null) {
    return null
  }
  return kind === 'SQUAD_RALLY' ? resp.squad : resp.alliance
}

/** 由上下界推出步长：至少 1，最多也就让一整段十下点完。 */
export function stepFor(min: number, max: number): number {
  const span = max - min
  if (span <= 1) {
    return 1
  }
  return Math.max(1, Math.ceil(span / 10))
}

/**
 * 进入某一档时的初始参数。**默认值取自读口**：人数用此刻能设的上限，
 * 准备时长用 `defaultPrepareMinutes`（服务端定的是最长档，理由在它自己的字段注释里）。
 * 读口还没到就返回 null —— 不猜一个默认值，那正是本要消灭的东西。
 *
 * <p><b>小队档恒为 null</b>：`SquadRallyReq` 根本没有 `maxMembers` / `prepareMinutes` 两个字段
 * （小队就那几个人，窗口由服务端定），所以那一档画两个人数与时长的加减号是一组
 * **点了什么都不吃的控件**。参数行只在写口真的收这两个数的那一档出现。
 * 小队档仍然要用 `policyFor` 读 `canStart` / `reason` —— 能不能发起与参数是两件事。
 */
export function defaultParams(kind: RallyKind, resp: RallyPolicyResp | null): RallyParams | null {
  if (kind !== 'ALLIANCE_RALLY') {
    return null
  }
  const policy = policyFor(kind, resp)
  if (policy === null) {
    return null
  }
  const membersMin = policy.minMembers
  const membersMax = Math.max(membersMin, policy.maxMembers)
  const prepareMin = policy.minPrepareMinutes
  const prepareMax = Math.max(prepareMin, policy.maxPrepareMinutes)
  return {
    members: {
      value: membersMax, min: membersMin, max: membersMax, step: stepFor(membersMin, membersMax),
    },
    prepare: {
      value: clampTo(policy.defaultPrepareMinutes, prepareMin, prepareMax),
      min: prepareMin, max: prepareMax, step: stepFor(prepareMin, prepareMax),
    },
  }
}

/** 点 −/＋。越界夹住而不是拒绝：服务端对越界也是夹（客户端夹一次只是让手感一致）。 */
export function stepControl(control: RallyControl, direction: number): RallyControl {
  return {
    ...control,
    value: clampTo(control.value + direction * control.step, control.min, control.max),
  }
}

/**
 * 读口刷新后把玩家已经调过的值保留下来，只把越界的部分夹回新界内。
 * 不这么做的话：拉一次政策就把玩家的手感重置回默认值，等于告诉他"你刚才白点了"。
 */
export function rebind(params: RallyParams, kind: RallyKind, resp: RallyPolicyResp | null): RallyParams | null {
  const fresh = defaultParams(kind, resp)
  if (fresh === null) {
    return null
  }
  return {
    members: { ...fresh.members, value: clampTo(params.members.value, fresh.members.min, fresh.members.max) },
    prepare: { ...fresh.prepare, value: clampTo(params.prepare.value, fresh.prepare.min, fresh.prepare.max) },
  }
}

/** 不能发起时服务端给的那句人话。能发起时为 null。读口没到就是"暂时不知道"，也不置灰。 */
export function blockedReason(kind: RallyKind, resp: RallyPolicyResp | null): string | null {
  const policy = policyFor(kind, resp)
  if (policy === null || policy.canStart) {
    return null
  }
  return policy.reason
}

/** 标题前缀。目标名后面的「集结 / 出征」是界面词，不是服务端枚举的翻译。 */
export function kindTitle(kind: RallyKind): string {
  return kind === 'MARCH' ? '出征' : '集结'
}

/** 确认键上的字。三档各不相同，所以不能再用一个布尔量决定。 */
export function kindSubmitLabel(kind: RallyKind): string {
  if (kind === 'MARCH') {
    return '出征'
  }
  return kind === 'SQUAD_RALLY' ? '发起小队集结' : '发起联盟集结'
}

/** 切种类那颗的字：指向下一档，玩家点之前就知道会切到哪去。 */
export function kindToggleLabel(kind: RallyKind): string {
  if (kind === 'MARCH') {
    return '改成小队集结'
  }
  return kind === 'SQUAD_RALLY' ? '改成联盟集结' : '改回出征'
}

function clampTo(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
