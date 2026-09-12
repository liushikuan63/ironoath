package com.ironoath.web.power;

import com.ironoath.core.power.PowerCalculator;

/**
 * 职责：战力变更事件（B08 §1）。战力一旦变化就发布，排行榜 / 匹配池 / 联盟列表异步订阅。
 * 依赖：game-core 的 {@link PowerCalculator}（只借它的明细类型）。
 *
 * <p><b>为什么必须是事件而不是查询</b>（B08 §1 原文：禁止轮询）：
 * 轮询式的排行榜意味着「每 N 秒扫一遍全服玩家重算战力」，
 * 而战力计算要读城建 + 军队 + 武将三份存档 —— 1 万玩家就是 3 万次读，每 N 秒一次，
 * 其中 99% 的玩家战力根本没变。事件驱动把这个成本压到「只在真的变了的时候算一次」。
 *
 * <p><b>同步发布，异步消费</b>：Spring 的默认事件是同步的，发布者会等监听器跑完。
 * 这里的监听器只做一次 map put（见 {@link MatchPool}），所以同步是可接受的；
 * 若将来某个监听器要做重活（例如重算联盟排行），必须给它加 {@code @Async}，
 * 而不是让发布者变成异步 —— 发布者异步会让「我练了兵，战力却没变」成为一个可复现的时序 bug。
 *
 * @param playerId     战力发生变化的玩家
 * @param displayPower 展示战力（排行榜口径）
 * @param matchPower   匹配战力（圈层校验口径）
 * @param peakPower    历史峰值
 * @param breakdown    明细，UI 要能展开（B08 §1：玩家对「我为什么是这战力」极度敏感）
 * @param peakRaised   本次是否抬高了峰值。埋点用：压分行为的反向信号
 * @param atMillis     变更时刻（服务端时间戳）
 */
public record PowerChangedEvent(String playerId,
                                long displayPower,
                                long matchPower,
                                long peakPower,
                                PowerCalculator.PowerBreakdown breakdown,
                                boolean peakRaised,
                                long atMillis) {

    public PowerChangedEvent {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (breakdown == null) {
            throw new IllegalArgumentException("breakdown 不得为 null");
        }
        if (atMillis <= 0L) {
            throw new IllegalArgumentException("atMillis 必须为正的服务端时间戳，实际=" + atMillis);
        }
    }
}
