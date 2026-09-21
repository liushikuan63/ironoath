/**
 * 职责：体力那一屏的展示数据组装 —— 把 `GET /stamina` 的响应翻译成关卡面板要显示的字，
 *       以及「此刻点购买会发生什么」的那句人话。
 * 依赖：生成的协议类型（引擎无关，可脱离 Cocos 跑单测 —— B00 铁律 2）。
 *
 * <p><b>判定仍在服务端</b>：每日能买几次、扣多少金币、实际到账多少，全部由
 * `StaminaService` 裁定。这里只做协议明确要求客户端做的两件事：
 * <ol>
 *   <li>{@code buyCostGold == 0} 时把按钮<b>置灰而不是隐藏</b>（协议注释原话：体力是付费点，
 *       让玩家看见「明天还能买」比让它消失更有价值）；</li>
 *   <li>体力已满时不让这一按发生 —— 协议注释写明「超出上限的部分永久损失，而金币照扣」，
 *       并明确要求"客户端必须在购买前用 {@code StaminaResp.cap} 提示玩家继续购买会溢出"。
 *       提示做在按下去之前（置灰 + 一句原因），因为满了再买对玩家没有任何可得的东西：
 *       到账 0、金币照扣、溢出永久损失，这不是一个值得让人签字的决定。</li>
 * </ol>
 *
 * <p>"满没满"用的是服务端下发的 {@code current} 与 {@code cap} 两个数之比，
 * 不是客户端自己抄配置 —— 与 {@code StageRow.tappable} 直接照抄 {@code unlocked} 同一条做法。
 *
 * <p><b>不显示「今日还剩几次」</b>：响应只下发 {@code boughtToday} 与 {@code buyCostGold}，
 * 每日上限本身没下来。要显示"还剩几次"就得把配置抄进客户端 —— 那正是铁律 2 禁止的那件事。
 *
 * <p><b>倒计时只用服务端那两个字段之差</b>（{@code nextPointAt - serverNow}）：客户端自己的时钟
 * 与服务器有偏差，用自己时钟推算会让倒计时跳变（协议注释同一条）。
 */

import type { StaminaResp } from '../../net/generated/Protocol'

/** 体力那一屏。 */
export interface StaminaBoardView {
  /** 「体力 84/120」 */
  readonly valueText: string
  /** 恢复中：「每小时 +5 · 3 分 12 秒后 +1」；已满：「已满，不再恢复」 */
  readonly recoverText: string
  /** 「买 1 次 · 20 金币 · 今日已购 2 次」 */
  readonly buyText: string
  /** 金币余额那句人话。读不到时明说读不到，不写成 0（那会把有钱的玩家灰掉） */
  readonly goldText: string
  readonly buyBlocked: boolean
  /** 置灰原因。灰而不隐藏，所以必须有一句话解释为什么灰着 */
  readonly buyBlockedReason: string | null
}

/**
 * 组装体力那一屏。
 *
 * @param gold 玩家当前金币；null 表示资源明细还没读到 —— 此时不拦购买，只把"读不到"写出来
 * @param times 一次买几回（界面固定 1，参数留着让单测能问"买 5 次要多少金币"这种题）
 */
export function buildStaminaBoard(resp: StaminaResp, gold: number | null, times = 1): StaminaBoardView {
  if (resp === undefined || resp === null) {
    throw new Error('resp 不得为空')
  }
  const soldOut = resp.buyCostGold <= 0
  const full = resp.current >= resp.cap
  const poor = !soldOut && !full && gold !== null && gold < resp.buyCostGold
  return {
    valueText: `体力 ${resp.current}/${resp.cap}`,
    recoverText: recoverText(resp),
    buyText: soldOut
      ? `今日 ${resp.boughtToday} 次已买满`
      : `买 ${times} 次 · ${resp.buyCostGold} 金币 · 今日已购 ${resp.boughtToday} 次`,
    goldText: gold === null ? '金币余额还没读到' : `金币 ${gold}`,
    buyBlocked: soldOut || full || poor,
    buyBlockedReason: soldOut ? '今日购买次数已达上限，明天再来'
      : full ? '体力已满，现在买会白扣金币（溢出部分不结转）'
        : poor ? `金币不足，还差 ${resp.buyCostGold - (gold ?? 0)}` : null,
  }
}

function recoverText(resp: StaminaResp): string {
  if (resp.nextPointAt === null) {
    return '已满，不再恢复'
  }
  const remainingMs = resp.nextPointAt - resp.serverNow
  if (remainingMs <= 0) {
    // 服务端说还有一点要恢复、但那个时刻已经过了：这一格恢复还没结算进来。
    // 写"0 秒后"是句废话，写"即将恢复"才是玩家能照着行动的话
    return `每小时 +${resp.recoverPerHour} · 即将恢复`
  }
  const total = Math.ceil(remainingMs / 1000)
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `每小时 +${resp.recoverPerHour} · ${minutes} 分 ${seconds} 秒后 +1`
}
