// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一行活动在玩家视图里的样子。窗口内的进度、窗口结束时刻、领没领过 —— 都在这一格里。
 */
public record ActivityView(
        String id,   // 活动或签到配置的行标识。客户端原样回传给 `POST /activity/claim`，不把标识显示给玩家。
        String name,   // 活动名。**只在服务端与配置表里存一份**：客户端硬编码一份就会出现「改表了但界面没改」。
        ActivityState state,   // 服务端算出来的状态：`EXPIRED`=窗口已过（不可领）；`CLAIMABLE`=达标且本窗口没领过；`RUNNING`=其余（含已领过但窗口未结束 —— 那一格由 `claimed` 表达）。
        long progress,   // 当前窗口内的进度值。由事件推进（禁止轮询扫表），读取时不做「顺手推进一遍」。
        long goal,   // 服务端计算的达标目标。普通活动来自条件配置，签到来自周期规则；月满勤为业务月自然日数减允许漏签天数。已领取签到保留接受时目标。
        Long windowEndAt,   // 本窗口结束时刻（毫秒）。**可空**：配置表若出现时长为 0 的行（常驻活动），窗口无终点，下发 null 而不是编一个天文数字。
        boolean claimed,   // 本窗口是否已领过。领取幂等的判据在服务端（同 requestId 重放只发一次、同窗口重复领取被拒），这一格只是结果展示。
        ActivityCategory category,   // 可选分类，旧服务端缺少时按普通活动显示。
        List<ActivityReward> rewards,   // 服务端奖励预览，不代表已入账；每日签到展示本次可领的合计，条件与组成由details说明。
        String details,   // 服务端生成的参与条件、奖励组成、刷新与恢复说明，不包含内部业务key。
        Boolean automatic,   // 额外签到奖励随每日入口自动接受，不提供独立领取按钮。
        Boolean processing)   // 奖励已接受但尚有发放待恢复；资格与余额收据不会重发。
{
}
