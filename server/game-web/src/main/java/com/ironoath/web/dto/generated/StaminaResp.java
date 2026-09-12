// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /stamina 的响应（B09 §5）。
 *
 * **对 B09 契约的一处偏离**：文档给的是 `recoverPerMin`，但恢复速率是「每 6 分钟 1 点」，换算成每分钟就是 1/6 点 —— 一个 long 装不下，而铁律禁止用 double 表示这类数值。所以这里下发 `recoverPerHour`（整数，精确）加 `nextPointAt`（下一点恢复的服务端时刻）。UI 真正需要的是倒计时，`nextPointAt` 直接就是它，比一个每分钟速率更有用。
 */
public record StaminaResp(
        long current,   // 当前体力（已完成惰性恢复结算）
        long cap,   // 体力上限 = STAMINA_CAP_BASE + STAMINA_CAP_PER_LEVEL × 主城等级。溢出部分永久损失，不结转（B09 §5 禁止项：不要让体力溢出超过上限）
        long recoverPerHour,   // 每小时恢复点数，来自 resource 表 STAMINA 行的 basePerHour
        Long nextPointAt,   // 下一点恢复的服务端毫秒时刻；已满时为 null（满了就不该再显示倒计时）。客户端不得用自己的时钟推算：本字段与 serverNow 一起下发，倒计时用两者之差，这样校时误差不会让倒计时跳变
        long boughtToday,   // 今日已购买次数。下发它是为了让客户端能显示「今日还剩 N 次」，但判定仍然只在服务端做（铁律 2）
        long buyCostGold,   // 下一次购买所需金币。已达每日上限时为 0，客户端据此把按钮置灰 —— 置灰而不是隐藏：体力是付费点，让玩家看见「明天还能买」比让它消失更有价值
        long serverNow)
{
}
