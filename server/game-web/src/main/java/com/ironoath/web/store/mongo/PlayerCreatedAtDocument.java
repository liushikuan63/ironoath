package com.ironoath.web.store.mongo;

import com.ironoath.core.player.PlayerRepository;
import org.springframework.data.annotation.Id;

/**
 * 职责：{@code player} 集合里<b>建档时刻</b>那一列的读模型（持久化层专用）。
 * 依赖：{@link PlayerRepository#findCreatedAt} 这条窄读口。
 *
 * <p>为什么又要一个读模型而不是把 {@code findByPlayerId} 的结果取一位：那条查询带
 * {@code fields().include("createdAt")}，返回的文档里没有 {@code avatarId} 这类原始类型分量，
 * 拿 {@link PlayerDocument} 承接会当场构造不出来（与 {@link PlayerBriefDocument} 同一条理由）。
 * 顺带把"这一口只许拿一个时间戳"写在类型上：想多拿一位就得改这个 record，而改它会被
 * {@code PlayerBriefProjectionQueryTest} 的投影列判据与等价用例一起看见。
 *
 * @param playerId  主键，即玩家 id
 * @param createdAt 建档时刻（老文档缺这一位时读成 0，由调用方按"没有个人锚"处理）
 */
public record PlayerCreatedAtDocument(@Id String playerId, long createdAt) {
}
