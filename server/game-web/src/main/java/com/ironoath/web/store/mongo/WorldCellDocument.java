package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/**
 * 职责：被玩家消耗掉的格子（资源点采空 / 野怪被打掉）。
 * 依赖：无。
 *
 * <p>只记"被改动过的格子"，整张地图由种子确定性生成 —— 262144 格里只有被改过的那些要落库。
 *
 * <p>{@code chunkKey} 是一列派生值（与 {@code coordKey} 同源，由它按 {@code WORLD_CHUNK_SIZE}
 * 算出），存在的唯一理由是让"某个 chunk 里有哪些空格"走索引：不存它就只能把全部消耗格捞回
 * 应用层再筛，而 {@code consumedInChunk} 是每次组装 viewport 都会调的热路径。
 */
public record WorldCellDocument(
        @Id String coordKey,
        String chunkKey,
        long consumedAt) {

    /** 集合名。 */
    public static final String COLLECTION = "world_cell";
}
