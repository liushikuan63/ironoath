package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：某个玩家已探索的 chunk 集合（迷雾）。
 * 依赖：无。
 *
 * <p>一个玩家一份文档，不是"一格一文档"：迷雾的读写单位天然是整张图（进世界要一次性拿到
 * 自己见过的全部块，{@code viewport} 也是整块整块判定的）。拆成一格一行会让进世界这一次读
 * 变成一次几万条的扫描。
 *
 * <p>{@code chunks} 用 List 而不是 Set 落库：Mongo 的数组不保证去重顺序，去重与幂等在
 * {@code FogOfWar} 领域对象里已经做了（它是唯一知道"见过就是见过"这件事的地方）。
 */
public record WorldFogDocument(
        @Id String playerId,
        List<String> chunks,
        long version) {

    /** 集合名。 */
    public static final String COLLECTION = "world_fog";
}
