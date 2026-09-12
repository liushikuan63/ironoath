package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/**
 * 职责：玩家城位置的 MongoDB 文档（一城一文档，主键是玩家）。
 * 依赖：无（坐标摊平成三个标量列）。
 *
 * <p><b>为什么以 playerId 为 {@code _id} 而 {@code coordKey} 只加唯一索引</b>：
 * 一条"谁占哪一格"的事实如果有两份存储（玩家→格 与 格→玩家），就必然出现"改了一边漏了另一边"，
 * 而它的症状是一座<b>幽灵城</b>：格子上还挂着人，点进去却搜不到；或反过来，玩家自己有城
 * 却在别人的地图上不存在。放在同一份文档里，"迁城"就是一次原子改档，旧格随文档一起走，
 * 没有需要记得解绑的第二份索引。
 *
 * <p>两个唯一约束各挡一件事：{@code _id} 挡"同一座城落两次"，{@code coordKey} 挡
 * "两家人的城叠在同一格"（内存实现靠两次 {@code putIfAbsent} 达到同样的效果，
 * 但那两步之间存在与 {@code WorldRepository#moveCity} 交错的可能）。
 */
public record WorldCityDocument(
        @Id String playerId,
        String coordKey,
        long x,
        long y) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "world_city";

    /** 一格一玩家的那条唯一索引（名字与 MongoIndexes 里保持同一条）。 */
    public static final String INDEX_COORD_KEY = "uk_world_city_coord_key";
}
