/**
 * 职责：退出与解散这四行的判定（B26 S3）—— 纯逻辑，不碰引擎。
 * 依赖：game/social/PermissionGates（解散要看权限位），其余只吃布尔。
 *
 * <p>两件事放在这里而不是视图里：
 * ① **解散要权限、退出不要** —— 退出是每个成员的权利（`role_permission` 表里根本没有 LEAVE 这一位），
 *    而解散看 `DISBAND_SQUAD` / `DISBAND_ALLIANCE`。把两条写成一样的门槛，等于把能退队的人关在队里。
 * ② **第二下才算数** —— 第一下只把键改成「确认退出」，不发请求。这类不可逆动作一键就生效，
 *    误触的代价是玩家整个联盟没了；而"要不要再确认一次"是界面行为，服务端不该为此多一个参数。
 */
import type { Gate } from './PermissionGates'

export type ExitScope = 'squad' | 'alliance'
export type ExitAction = 'leave' | 'disband'

/** 一行（退出或解散）在面板上的样子。 */
export interface ExitEntry {
  readonly actionText: string
  readonly enabled: boolean
  /** 灰的时候写为什么灰（服务端那句人话，不是权限码）。 */
  readonly detailText: string
}

/** 四个动作的固定名字（两行 × 两个层级）。 */
export function exitLabel(scope: ExitScope, action: ExitAction, armed: boolean): string {
  const who = scope === 'squad' ? '小队' : '联盟'
  if (action === 'leave') {
    return armed ? `确认退出${who}` : `退出${who}`
  }
  return armed ? `确认解散${who}` : `解散${who}`
}

/**
 * 某一行的样子。
 *
 * @param gate 解散用的权限门（退出传 null：那是每个成员的权利，服务端没有这一位）
 * @param armed 是否已经按过第一下
 */
export function exitEntry(scope: ExitScope, action: ExitAction, joined: boolean,
                          gate: Gate | null, armed: boolean): ExitEntry {
  const text = exitLabel(scope, action, armed)
  if (!joined) {
    return { actionText: text, enabled: false, detailText: '还没加入，不用退出' }
  }
  if (gate !== null && !gate.allowed) {
    return { actionText: text, enabled: false, detailText: gate.reason ?? '现在不能解散' }
  }
  // 按过第一下之后行上就该说清"再点一次会发生什么"，而不是让玩家对着一个变字的按钮猜
  return {
    actionText: text,
    enabled: true,
    detailText: armed
      ? (action === 'leave' ? '再点一次真的离开' : '再点一次组织就没了，成员各自散去')
      : '',
  }
}

/** 当前按下了第一下的那一行（null = 没有）。 */
export interface ExitKey {
  readonly scope: ExitScope
  readonly action: ExitAction
}
