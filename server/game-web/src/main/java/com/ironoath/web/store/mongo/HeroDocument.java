package com.ironoath.web.store.mongo;

import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.Lineup;
import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：武将存档的 MongoDB 文档模型。
 * 依赖：{@link HeroRoster.HeroSnapshot} / {@link Lineup}。
 *
 * <p>载荷字段直接取领域快照，不另立一套镜像 {@code *Doc}：理由与 {@link CityDocument} 同一条 ——
 * 这份存档只有一个访问键（playerId）、一种访问方式（整档读写），抄一份镜像记录换不来任何查询能力，
 * 只会多出一个"少抄一个养成字段就静默丢档"的地方。
 *
 * @param playerId 主键
 * @param version  乐观锁版本号（端口注释写明它是必需的，不是可选加固：多实例下唯一能拦住并发覆盖的就是它）
 * @param heroes   武将养成状态全集
 * @param lineups  编队预设（含空位）
 */
public record HeroDocument(
        @Id String playerId,
        long version,
        List<HeroRoster.HeroSnapshot> heroes,
        List<Lineup> lineups) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "hero";

    static HeroDocument fromDomain(String playerId, long version, HeroRoster roster) {
        HeroRoster.Snapshot snapshot = roster.snapshot();
        return new HeroDocument(playerId, version, snapshot.heroes(), snapshot.lineups());
    }

    /** 每次调用都新建一份，所以读出去的不是库里的活对象。 */
    HeroRoster toDomain() {
        return HeroRoster.fromSnapshot(new HeroRoster.Snapshot(heroes, lineups));
    }
}
