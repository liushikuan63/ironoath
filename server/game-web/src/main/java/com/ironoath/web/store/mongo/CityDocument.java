package com.ironoath.web.store.mongo;

import com.ironoath.core.city.CityState;
import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：城建存档的 MongoDB 文档模型。
 * 依赖：{@link CityState.Snapshot} / {@link CityState.BuildingSnapshot}。
 *
 * <p><b>为什么这里直接复用领域的快照记录，而不是像 {@link PlayerDocument} 那样再抄一套 *Doc 子记录</b>：
 * 玩家表那么做是因为它要按字段建索引（deviceId 唯一索引）、要把热字段拆出来查询。
 * 城建档只有一个访问键（playerId）和一个访问方式（整档读、整档写），
 * 抄一套镜像记录不会换来任何查询能力，只会多出一个"少抄一个字段就静默丢档"的地方 ——
 * 而那正是 {@code CityState.snapshot()} 被提出来要消灭的东西。
 * 以后如果要给某个建筑字段建索引，再拆不迟，那时它有明确的理由。
 *
 * @param playerId   主键（一个玩家一份城建存档）
 * @param version    乐观锁版本号，语义与 {@code CityRepository.save} 的 expectedVersion 一致
 * @param buildings  建筑全集
 * @param extraQueues 已付费开启的额外队列数
 */
public record CityDocument(
        @Id String playerId,
        long version,
        List<CityState.BuildingSnapshot> buildings,
        int extraQueues) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "city";

    /** 由领域快照造一份待落库的文档。 */
    static CityDocument fromDomain(String playerId, long version, CityState state) {
        CityState.Snapshot snapshot = state.snapshot();
        return new CityDocument(playerId, version, snapshot.buildings(), snapshot.extraQueues());
    }

    /** 还原成领域对象（每次调用都得到一个全新实例，等价于内存版的深拷贝）。 */
    CityState toDomain() {
        return CityState.fromSnapshot(new CityState.Snapshot(buildings, extraQueues));
    }
}
