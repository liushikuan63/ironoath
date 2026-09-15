/**
 * 职责：客户端版本闸门的判定（B16 §5「强制更新」）。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么单独一层而不是写在 GameBootstrap 里</b>：`scene/` 在本仓库不参与 CI，
 * 判定写在那边就只有"在编辑器里打开过"才算验过。判定抽到这里之后，"什么情况下必须拦住玩家"
 * 是一条能被 node:test 直接钉住的规则，场景层只剩"照着画"。
 *
 * <p><b>这条闸门以前根本没生效</b>：协议里写着「forceUpdate=true 时客户端必须停在提示页，
 * 不得进入游戏」，而客户端此前只在启动时取过 `trackPolicy`，`forceUpdate` 与 `notice`
 * 两个字段**全仓零引用** —— 服务端算出"该拦"，客户端照样登录、照样拉十个面板。
 * 这正是本项目一直防的「机制默认没生效」：卡口全绿、判定也在，就是没人读它。
 *
 * <p><b>问不到服务端时不拦</b>：版本查询失败（网络/业务错误）不该把玩家挡在门外 ——
 * 那与"内容送检没问成就放行"是同一条取舍：外部依赖抖一下就把全服变成打不开的提示页，
 * 比放行一个可能过旧的版本更糟。但这次判定必须**看得出来没问成**（`serverSeen=false`），
 * 否则"闸门从没生效"和"闸门判定为放行"在日志里长得一模一样。
 */

import type { NetOutcome } from '../../net/NetModule'
import type { AppVersionResp } from '../../net/generated/OpsProtocol'

/** 闸门结论。 */
export interface UpdateGateDecision {
  /** true = 停在提示页，不许登录、不许进入游戏 */
  readonly blocked: boolean
  /** 拦下来时给玩家看的那句话；不拦时为 null */
  readonly notice: string | null
  /** 服务端认为的最新版本；没问到时等于本地版本 */
  readonly latest: string
  /** 有没有真的问到服务端 —— 没问到时为 false，用来区分"判定为放行"与"根本没判" */
  readonly serverSeen: boolean
}

/**
 * 按服务端回答判定是否拦下本次启动。
 *
 * @param outcome       `/ops/app/version` 的结果；null 表示这次压根没发出去
 * @param clientVersion 当前客户端版本，用于兜底文案（服务端没给 notice 时不能说空话）
 */
export function decideUpdateGate(
  outcome: NetOutcome<AppVersionResp> | null,
  clientVersion: string,
): UpdateGateDecision {
  if (outcome === null || outcome.kind !== 'ok') {
    return { blocked: false, notice: null, latest: clientVersion, serverSeen: false }
  }
  const resp = outcome.data
  if (!resp.forceUpdate) {
    return { blocked: false, notice: null, latest: resp.latest, serverSeen: true }
  }
  // 拦下来时必须说得出为什么：服务端没配文案（或配成空白）时兜一句带版本号的说明，
  // 而不是让玩家对着一块空白提示页。协议里 notice 在 forceUpdate=true 时本该非空，
  // 这一手是防"配漏了"——被拦的人此刻最需要的是一个能读懂的理由。
  const configured = resp.notice === null || resp.notice === undefined ? '' : resp.notice.trim()
  return {
    blocked: true,
    notice: configured.length > 0
      ? configured
      : `当前版本 ${clientVersion} 过低，请更新到 ${resp.latest} 后再进入游戏`,
    latest: resp.latest,
    serverSeen: true,
  }
}
