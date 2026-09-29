/**
 * 职责：服务端时刻 → 「N 分钟前」这类人话。纯逻辑，不碰引擎。
 * 依赖：无。
 *
 * <p><b>为什么从抽卡记录里抽出来单独放一份</b>：这份算式原先住在 `game/gacha/GachaHistory.ts`，
 * 而国库流水（B13 §3）要的是同一种话 —— 同一个格式化函数只能有一个家，
 * 抄第二份就是留一个将来必然分叉的口径（#281 那一族的成因：同一个格式化函数被抄了四遍）。
 *
 * <p>分级刻意只有四档：这些是流水，玩家要答的问题是"这是我刚才那次，还是昨天那次"。
 */

/** 相对时间的下限语。 */
export const JUST_NOW = '刚刚'

/**
 * 距今多久（毫秒 → 人话）。
 *
 * <p><b>负数一律说「刚刚」</b>：记录时刻晚于 `serverNow` 只可能是两端时钟不同步，
 * 而算出「-3 分钟前」或「0 天前」都会让玩家以为记录坏了。
 *
 * <p>入参是 `serverNow - at`（**由调用方用同源的两个服务端时刻相减**）：
 * 客户端不引本机时钟（铁律 5），所以这个函数自己拿不到"现在"。
 */
export function elapsedText(elapsedMs: number): string {
  if (!Number.isFinite(elapsedMs) || elapsedMs < 0) {
    return JUST_NOW
  }
  const minutes = Math.floor(elapsedMs / 60000)
  if (minutes < 1) {
    return JUST_NOW
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`
  }
  const hours = Math.floor(minutes / 60)
  if (hours < 24) {
    return `${hours} 小时前`
  }
  return `${Math.floor(hours / 24)} 天前`
}
