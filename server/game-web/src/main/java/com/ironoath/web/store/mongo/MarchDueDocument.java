package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/**
 * 职责：行军到期事件的一条 MongoDB 文档。
 * 依赖：无。
 *
 * <p>队列不是行军本体（本体在 {@code march} 集合），它是"什么时候该扫描哪支行军"的索引。
 * 丢失它不会让存档消失，但会让所有在途队伍永远不再推进 —— 症状是"队伍停在半路、
 * 既不前进也不到家"，比丢档更难发现。所以它必须与行军一样进 Mongo。
 */
public record MarchDueDocument(@Id String marchId, long dueAt) {

    public static final String COLLECTION = "march_due";
}