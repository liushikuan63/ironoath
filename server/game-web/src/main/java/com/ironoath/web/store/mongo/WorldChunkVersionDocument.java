package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/**
 * 职责：chunk 版本号的 MongoDB 文档（一块一文档，只做原子自增）。
 * 依赖：无。
 *
 * <p>版本号是增量下发的全部依据（B07 验收 6：无变化返回 304、二次请求实体数为 0）。
 * 一文档一键 + {@code $inc} 是让"两个实例同时改动同一块"不会互相回退的唯一办法 ——
 * 读出来加一再写回的话，两次变更可能只涨一格，而那一格变化的实体就永远不会被客户端重新拉取。
 */
public record WorldChunkVersionDocument(
        @Id String chunkKey,
        long version) {

    /** 集合名。 */
    public static final String COLLECTION = "world_chunk_version";
}
