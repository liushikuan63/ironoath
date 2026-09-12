package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import java.util.Map;

/**
 * 职责：背包的 MongoDB 文档模型。
 * 依赖：无（纯数据 + Spring Data 注解）。
 *
 * <p>字段直接取 {@code Inventory.snapshot()} 的产物（道具 id → 数量）加容量：
 * 那对 snapshot/restore 本来就在领域里，内存版深拷贝也走它，所以这里不再另立一份"什么算完整背包"
 * （理由与 {@link CityDocument} 同源，也同样是 #34 契约要避免的两份定义）。
 *
 * @param playerId     主键
 * @param version      乐观锁版本号
 * @param capacityMax  容量上限（会随扩容增长，属于存档状态，不能从配置反推）
 * @param counts       道具 id → 数量；空 map 表示空背包（不省略字段，省了就要在读取处判缺字段）
 */
public record InventoryDocument(
        @Id String playerId,
        long version,
        int capacityMax,
        Map<String, Long> counts) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "inventory";
}
