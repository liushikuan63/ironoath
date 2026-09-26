/**
 * 职责：体力详情（B09 §5）的**纯视图模型** —— 把 `/stamina` 的读数翻成面板要画的几行字。
 * 依赖：无（纯函数，node 里可单测；视图在 node 环境跑不起来，所以判定与文案都放这里）。
 *
 * <p><b>三条来自协议本身的硬要求</b>（不是我自己加的口径）：
 * <ol>
 *   <li>倒计时必须用 `nextPointAt - serverNow` 算，**不得用本机时钟** —— 协议原话是"本字段与 serverNow
 *       一起下发，倒计时用两者之差，这样校时误差不会让倒计时跳变"；</li>
 *   <li>满了（`nextPointAt == null`）就不显示倒计时 —— "满了就不该再显示倒计时"；</li>
 *   <li>`buyCostGold == 0`（今日上限已到）时按钮**置灰而不是隐藏** —— 协议明写理由："体力是付费点，
 *       让玩家看见「明天还能买」比让它消失更有价值"。</li>
 * </ol>
 */

/** `/stamina` 的读数（只取这个模块要用的字段，避免把协议类型透进表现层）。 */
export interface StaminaReading {
  readonly current: number
  readonly cap: number
  readonly recoverPerHour: number
  readonly nextPointAt: number | null
  readonly boughtToday: number
  readonly buyCostGold: number
  readonly serverNow: number
}

export interface StaminaDetailView {
  /** 「体力 87/100」 */
  readonly titleText: string
  /** 「每小时恢复 12 点」 */
  readonly recoverText: string
  /** 「下一点恢复：03:12」；已满时为 null（协议要求：满了不显示倒计时） */
  readonly nextText: string | null
  /** 「今日已买 2 次」 */
  readonly boughtText: string
  /** 按钮文案：「买 1 次（50 金币）」 */
  readonly buyLabel: string
  /** 按钮能不能点：金币够、且没到今日上限（**判定仍以服务端为准**，这里只管置灰） */
  readonly buyEnabled: boolean
  /** 按钮下方那行说明；没有要说的就是 null */
  readonly noteText: string | null
}

/**
 * 把服务端读数翻成面板要画的东西。
 *
 * <p>**没有 `offsetMs` 参数**：倒计时按协议用 `nextPointAt - serverNow`（两个都来自同一次响应），
 * 不经过本机时间轴，所以也不需要校时偏移 —— 这正是协议那条"校时误差不会让倒计时跳变"想要的形态。
 *
 * @param gold 当前金币余额（资源条上的数）；只用来决定按钮置不置灰，判定仍在服务端
 */
export function buildStaminaDetail(resp: StaminaReading, gold: number): StaminaDetailView {
  const atCap = resp.current >= resp.cap
  const nextText = resp.nextPointAt === null || atCap
    ? null
    : `下一点恢复：${formatCountdown(resp.nextPointAt - resp.serverNow)}`
  const soldOut = resp.buyCostGold <= 0
  return {
    titleText: `体力 ${resp.current}/${resp.cap}`,
    recoverText: resp.recoverPerHour > 0
      ? `每小时恢复 ${resp.recoverPerHour} 点`
      : '当前不自动恢复（恢复速率来自配置表）',
    nextText,
    boughtText: `今日已买 ${resp.boughtToday} 次`,
    buyLabel: soldOut ? '今日已买满' : `买 1 次（${resp.buyCostGold} 金币）`,
    // 置灰的两个理由分开表达：到上限、或金币不够 —— 都不隐藏按钮（协议要求）
    buyEnabled: !soldOut && gold >= resp.buyCostGold,
    noteText: soldOut
      ? '明天还能再买'
      : gold < resp.buyCostGold
        ? `金币不足（还差 ${resp.buyCostGold - gold}）`
        : atCap
          ? '体力已满，买了会溢出损失'
          : null,
  }
}

/** 毫秒 → `mm:ss`（超过一小时给 `H:mm:ss`）。负数按 0 处理：倒计时不许出现负号。 */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  const pad = (value: number): string => String(value).padStart(2, '0')
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}
