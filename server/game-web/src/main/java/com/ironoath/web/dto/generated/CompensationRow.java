// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一笔发奖欠账。给的是「够不够按原样补发」：谁、哪次发奖、哪几件没出去、为什么。
 */
public record CompensationRow(
        String compensationId,   // 台账主键，形如 comp_xxx。销账时要带它。
        String playerId,   // 欠谁的。
        String source,   // 来源系统（quest / activity / battle / mail / shop …）。风控归因按它，不看这个就只知道玩家多了少了不知道是哪个系统发的。
        String sourceRef,   // 来源的具体引用（任务 id、订单 id…），没有时是空串而不是 null —— 空串表示「这条链路本来就没有引用」，null 会被读成「字段没写全」。
        String traceId,   // 当时那次请求的链路 id（铁律 10）。拿它可以把日志里那条 ERROR 与这一行对上。
        String reason,   // 失败原因摘要，没有原因时是「业务校验未通过」。**给运维看的一句人话，不是异常栈** —— 栈在日志里，按 traceId 找。
        long createdAt,   // 记账时刻（毫秒）。欠了多久是排优先级的第一依据，与支付负债同一口径。
        List<CompensationRewardView> items)   // 没发出去的明细。**至少一条**：一条「欠 0 件」的记录只会来自把已发放误当待补偿的调用点，那种错误在记账当场就该响。
{
}
