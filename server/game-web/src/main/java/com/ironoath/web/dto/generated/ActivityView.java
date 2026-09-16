// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一行活动在玩家视图里的样子。窗口内的进度、窗口结束时刻、领没领过 —— 都在这一格里。
 */
public record ActivityView(
        String id,   // `activity.json` 的 id。客户端把它回传给 `POST /activity/claim`。
        String name,   // 活动名。**只在服务端与配置表里存一份**：客户端硬编码一份就会出现「改表了但界面没改」。
        ActivityState state,   // 服务端算出来的状态：`EXPIRED`=窗口已过（不可领）；`CLAIMABLE`=达标且本窗口没领过；`RUNNING`=其余（含已领过但窗口未结束 —— 那一格由 `claimed` 表达）。
        long progress,   // 当前窗口内的进度值。由事件推进（禁止轮询扫表），读取时不做「顺手推进一遍」。
        long goal,   // 达标目标，来自 `activity.json` 的 `conditionValue`。它下发而不是让客户端读表：客户端读表意味着表与包的版本必须永远同步。
        Long windowEndAt,   // 本窗口结束时刻（毫秒）。**可空**：配置表若出现时长为 0 的行（常驻活动），窗口无终点，下发 null 而不是编一个天文数字。
        boolean claimed)   // 本窗口是否已领过。领取幂等的判据在服务端（同 requestId 重放只发一次、同窗口重复领取被拒），这一格只是结果展示。
{
}
