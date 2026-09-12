package com.ironoath.web.store.mongo;

import com.ironoath.core.gacha.GachaState;
import org.springframework.data.annotation.Id;

/**
 * 职责：抽卡保底进度的 MongoDB 文档模型。
 * 依赖：{@link GachaState}。
 *
 * <p>主键是 {@code playerId + 分隔符 + poolId} 拼出的复合键：保底是<b>按卡池</b>记的，
 * 一个玩家有 N 个池就有 N 份进度。这也是 {@code GachaStateRepository} 不塞进
 * {@code HeroRoster} 的原因（见该端口的类注释）—— 塞进去会让"抽一次卡"和"升一次武将"
 * 争同一个乐观锁版本号，玩家一边抽卡一边喂经验书就会频繁撞版本。
 *
 * <p>本存档<b>没有版本号</b>，与端口一致：抽卡整段在玩家锁内，且计数字段是单调递增或按规则清零，
 * 玩家锁（多实例下是 Redisson 锁）已经排除了并发。
 */
public record GachaStateDocument(
        @Id String bucketKey,
        String playerId,
        String poolId,
        long ssrCounter,
        long srCounter,
        long nonUpStreak,
        long lifetimeDraws) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "gacha_state";

    /**
     * 分隔符用竖线而不是冒号：{@code playerId} 形如 {@code P<32位hex>}、{@code poolId} 是表里的
     * 行 id，两者都不含竖线，所以拼出来的键唯一且可反解。
     */
    public static String keyOf(String playerId, String poolId) {
        return playerId + "|" + poolId;
    }

    static GachaStateDocument fromDomain(GachaState state) {
        return new GachaStateDocument(keyOf(state.playerId(), state.poolId()), state.playerId(),
                state.poolId(), state.ssrCounter(), state.srCounter(), state.nonUpStreak(),
                state.lifetimeDraws());
    }

    GachaState toDomain() {
        return new GachaState(playerId, poolId, ssrCounter, srCounter, nonUpStreak, lifetimeDraws);
    }
}
