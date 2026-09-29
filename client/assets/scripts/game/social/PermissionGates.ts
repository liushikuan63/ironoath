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

/** 三个层级。取值与服务端 `scope` 参数一致（NATION 是 V13 补上的那一档）。 */
export type SocialScope = 'SQUAD' | 'ALLIANCE' | 'NATION'

export interface PermissionState {
  /** 我在小队层的权限码（没读到 = 空数组，且 `loaded` 为 false） */
  readonly squad: readonly string[]
  /** 我在联盟层的权限码 */
  readonly alliance: readonly string[]
  /** 我在国家层的权限码（V13：`scope=NATION` 此前恒定回空，见 NationPermissions） */
  readonly nation: readonly string[]
  /** 三层的职位原值（服务端 `role` 字段），只用于"我是什么职位"这一行说明 */
  readonly squadRole: string | null
  readonly allianceRole: string | null
  readonly nationRole: string | null
  /** 小队 + 联盟两份都拉到过才算读到过 —— 只拉到一个就放开按钮，等于拿缺的那一半去猜 */
  readonly loaded: boolean
  /**
   * 国家那一份**单独一位**，刻意不并进 {@link loaded}：
   * 并进去的后果是"国家权限还没回来"会把联盟页的踢人按钮一起卡住 ——
   * 两个页面之间没有任何关系，一次慢请求不该让另一页变成只读。
   */
  readonly nationLoaded: boolean
}

export const EMPTY_PERMISSIONS: PermissionState = {
  squad: [], alliance: [], nation: [],
  squadRole: null, allianceRole: null, nationRole: null,
  loaded: false, nationLoaded: false,
}

/** 收到一个 scope 的回执后合并进状态（另一个 scope 那份原样留着）。 */
export function withPermissionScope(state: PermissionState, scope: SocialScope,
  codes: readonly string[], role: string | null, bothLoaded: boolean): PermissionState {
  switch (scope) {
    case 'SQUAD':
      return { ...state, squad: codes, squadRole: role, loaded: bothLoaded }
    case 'ALLIANCE':
      return { ...state, alliance: codes, allianceRole: role, loaded: bothLoaded }
    case 'NATION':
      // `bothLoaded` 对小/盟两层的含义在这里不适用：国家那一份有自己的位
      return { ...state, nation: codes, nationRole: role, nationLoaded: true }
    default:
      return state
  }
}

/** 某一层的权限码。**三档都要显式写出来**：用"非 SQUAD 就是联盟"那种写法，加一层就会静默读错槽位。 */
export function codesOf(state: PermissionState, scope: SocialScope): readonly string[] {
  switch (scope) {
    case 'SQUAD':
      return state.squad
    case 'ALLIANCE':
      return state.alliance
    case 'NATION':
      return state.nation
    default:
      return []
  }
}

/** 清掉国家那一份。退国 / 亡国之后必须清：留着就是让一个已经离开的国家继续授权。 */
export function withoutNationPermissions(state: PermissionState): PermissionState {
  return { ...state, nation: [], nationRole: null, nationLoaded: false }
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
  const loaded = scope === 'NATION' ? state.nationLoaded : state.loaded
  if (!loaded) {
    return { allowed: false, reason: '权限还没读到' }
  }
  if (codesOf(state, scope).includes(code)) {
    return { allowed: true, reason: null }
  }
  return { allowed: false, reason: '你当前的职位不能做这件事' }
}

/** 「我是什么职位」那一行（职位码不印原值：与权限码同一条理由）。 */
export function roleText(state: PermissionState, scope: SocialScope): string {
  if (scope === 'NATION') {
    // 国家那一页**不用这一行**：官职的中文名由 `NationPanel.officeLabel` 从国家视图的
    // `myOffice` 取（那一份才是权威，而且它有"无官职/未知官职"两种回退）。
    // 这里只保证不把裸枚举印出去，也不谎称"读取中"
    if (!state.nationLoaded) {
      return '职位读取中'
    }
    const role = state.nationRole
    if (role === null || role === undefined || role === 'NONE') {
      return '未加入'
    }
    return role === 'MEMBER' ? '成员' : '未知职位'
  }
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
