package com.ironoath.core.reward;

/**
 * 职责：发奖上下文 —— 记录这次发奖的来源与溢出策略。
 * 依赖：无（纯数据）。
 *
 * <p>{@code source} 不是可选的装饰：它是埋点与风控的主键。
 * 「玩家资源突然多了 100 万」这类问题，唯一的排查路径就是按 source 反查是哪个系统发的。
 * 没有 source 的发奖等于凭空造币，事后无法归因。
 *
 * @param source           来源系统，如 quest / activity / battle / mail / compensation
 * @param sourceRef        来源的具体引用（任务 id、战报 id、邮件 id），用于精确追溯
 * @param traceId          链路 id，与 HTTP 响应的 traceId 一致（铁律 10）
 * @param overflowToMail   溢出（背包满 / 资源超上限）是否转邮件补发。
 *                         false 时溢出部分直接丢弃 —— 只用于「不可补发」的场景，
 *                         例如排行榜实时结算，此时补发邮件反而会让玩家困惑
 */
public record RewardContext(String source, String sourceRef, String traceId, boolean overflowToMail) {

    public RewardContext {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不得为空：没有来源的发奖无法归因与风控");
        }
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId 不得为空：发奖必须可追溯到一次具体请求");
        }
        sourceRef = sourceRef == null ? "" : sourceRef;
    }

    /** 溢出转邮件（默认策略）。 */
    public static RewardContext toMail(String source, String sourceRef, String traceId) {
        return new RewardContext(source, sourceRef, traceId, true);
    }

    /** 溢出直接丢弃（仅用于不可补发的场景）。 */
    public static RewardContext discardOverflow(String source, String sourceRef, String traceId) {
        return new RewardContext(source, sourceRef, traceId, false);
    }
}
