/**
 * 职责：社交权限（`GET /social/permissions`）在客户端这一侧的状态与"能不能做"的判定（B26 S1、B13 验收 4）。
 * 依赖：只有类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>为什么两个 scope 必须分开存</b>：服务端一次只回一个 scope（`?scope=SQUAD|ALLIANCE`，
 * 默认 ALLIANCE），而小队与联盟的权限码**同名不同授予**（两边都有 `KICK_MEMBER`）。
 * 合成一份的后果是：在小队里能踢人，联盟那一页的「踢出」也跟着亮了 ——
 * 玩家点下去拿到一条拒绝，而界面刚刚明明说"你可以"。
 *
 * <p><b>这里不翻权限码的中文</b>：码表（`role_permission`）在服务端，客户端翻一份就是第二份真相，
 * 而且把 `KICK_MEMBER` 这种码印给玩家是 #268 那一族。理由只说人话：
 * "你当前的职位不能做这件事" —— 该说的是"你不行"，不是"哪个开关没开"。
 */

/** 两个层级。取值与服务端 `scope` 参数一致。 */
export type SocialScope = 'SQUAD' | 'ALLIANCE'

export interface PermissionState {
  /** 我在小队层的权限码（没读到 = 空数组，且 `loaded` 为 false） */
  readonly squad: readonly string[]
  /** 我在联盟层的权限码 */
  readonly alliance: readonly string[]
  /** 两层的职位原值（服务端 `role` 字段），只用于"我是什么职位"这一行说明 */
  readonly squadRole: string | null
  readonly allianceRole: string | null
  /** 两个 scope 都拉到过才算读到过 —— 只拉到一个就放开按钮，等于拿缺的那一半去猜 */
  readonly loaded: boolean
}

export const EMPTY_PERMISSIONS: PermissionState = {
  squad: [], alliance: [], squadRole: null, allianceRole: null, loaded: false,
}

/** 收到一个 scope 的回执后合并进状态（另一个 scope 那份原样留着）。 */
export function withPermissionScope(state: PermissionState, scope: SocialScope,
  codes: readonly string[], role: string | null, bothLoaded: boolean): PermissionState {
  return scope === 'SQUAD'
    ? { ...state, squad: codes, squadRole: role, loaded: bothLoaded }
    : { ...state, alliance: codes, allianceRole: role, loaded: bothLoaded }
}

/** 某一层的权限码。 */
export function codesOf(state: PermissionState, scope: SocialScope): readonly string[] {
  return scope === 'SQUAD' ? state.squad : state.alliance
}

export interface Gate {
  readonly allowed: boolean
  /** 不能做时给玩家看的那一句；能做时为 null */
  readonly reason: string | null
}

/**
 * 某个动作能不能做。**权限码原样比**（服务端下发的就是结论列表，客户端不参与算矩阵）。
 *
 * <p>没读到权限时不给放行也不给"你不行"：那时按钮该写"权限读取中"，
 * 说"你不行"会把一次读失败伪装成一条身份结论 —— 玩家会去申请升职，而真正要做的只是重进页面。
 */
export function gate(state: PermissionState, scope: SocialScope, code: string): Gate {
  if (!state.loaded) {
    return { allowed: false, reason: '权限还没读到' }
  }
  if (codesOf(state, scope).includes(code)) {
    return { allowed: true, reason: null }
  }
  return { allowed: false, reason: '你当前的职位不能做这件事' }
}

/** 「我是什么职位」那一行（职位码不印原值：与权限码同一条理由）。 */
export function roleText(state: PermissionState, scope: SocialScope): string {
  const role = scope === 'SQUAD' ? state.squadRole : state.allianceRole
  if (!state.loaded) {
    return '职位读取中'
  }
  // 读到了却没有职位 = 没在这个组织里。说"读取中"是句谎话，玩家会一直等一个不会来的结果
  if (role === null || role === undefined) {
    return '未加入'
  }
  // 只有两种职位是玩家听得懂的：队长/盟主 与 成员。其余取值（服务端加了新职位）如实说"未知"
  if (role === 'LEADER') {
    return scope === 'SQUAD' ? '队长' : '盟主'
  }
  return role === 'MEMBER' ? '成员' : '未知职位'
}
