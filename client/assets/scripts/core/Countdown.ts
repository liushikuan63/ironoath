/**
 * 职责：服务端时间戳 → 本地倒计时（B03 §4、B05 §二的同一口径）。
 * 依赖：无（引擎无关，可脱离 Cocos 跑单测）。
 *
 * <p><b>为什么值得单独一个模块</b>：城建升级、兵种训练、伤兵治疗、行军返程用的是同一套换算，
 * 而这套换算有两条一旦写错就很难发现的纪律。散在各面板里各写一份，
 * 迟早会有一份漏掉其中一条 —— 而漏掉的那份不会报错，只会让玩家看到一个负数倒计时，
 * 或者一个「把手机时间调快就能立刻完成」的漏洞。
 *
 * <p><b>纪律一：换算必须走 TimeSync 的偏移，绝不直接拿本地 Date 与 finishAt 相减</b>（铁律 5）。
 * 玩家把手机时间调快一小时，升级就该真的还要一小时。
 *
 * <p><b>纪律二：绝不为负</b>。协议在 UnitView.remainingSeconds 上明写
 * 「客户端不得自行推算负数」，BuildingInstance.remainingSeconds 也在服务端做了同样的钳制。
 * 到点之后停在 0，并给出一句可执行的提示 —— 显示「-3 秒」会让玩家认为服务端算错了，
 * 显示「0 秒」又看不出该做什么。
 *
 * <p><b>本地每秒递减，不轮询服务端</b>：倒计时是纯显示需求，
 * 每秒发一次请求会把一个 UI 细节变成服务端的主要负载来源。
 */

/**
 * 距完成还剩多少毫秒；不在进行中时为 null。
 *
 * @param finishAt 服务端时间戳
 * @param offsetMs 服务端时刻 - 本地时刻，来源 core/TimeSync
 * @param localNow 当前本地时刻
 */
export function countdownMs(finishAt: number | null, offsetMs: number, localNow: number): number | null {
  if (finishAt === null || finishAt === undefined) {
    return null
  }
  if (!Number.isFinite(offsetMs)) {
    throw new RangeError(`offsetMs 必须是有限数，实际=${offsetMs}`)
  }
  return Math.max(0, finishAt - (localNow + offsetMs))
}

/**
 * 毫秒 → 「1小时02分03秒」/「02分03秒」。
 *
 * <p>到 0（或负数）时返回 {@code doneText}，由各面板给出自己的行动提示
 * （城建是「可收割」，军队是「已完成，可收取」）。措辞刻意不与状态文本重合，
 * 否则同一行上会出现两句几乎一样的话。
 *
 * <p>不足一秒显示 00 秒而不是毫秒：倒计时显示到毫秒会跳得看不清，
 * 而玩家真正关心的是「还要多久」，秒级足够。
 */
export function formatCountdown(remainingMs: number, doneText: string): string {
  if (remainingMs <= 0) {
    return doneText
  }
  const totalSeconds = Math.floor(remainingMs / 1000)
  const hours = Math.floor(totalSeconds / 3600)
  const minutes = Math.floor((totalSeconds % 3600) / 60)
  const seconds = totalSeconds % 60
  const mm = String(minutes).padStart(2, '0')
  const ss = String(seconds).padStart(2, '0')
  return hours > 0 ? `${hours}小时${mm}分${ss}秒` : `${mm}分${ss}秒`
}
