package com.ironoath.core.city;

import java.util.Optional;

/**
 * 职责：城建存档仓储端口（六边形架构的抽象侧）。
 * 依赖：无（纯接口）。
 *
 * <p>定义在 game-core 而不是 game-web，理由与 {@link com.ironoath.core.player.PlayerRepository} 相同：
 * 让城建逻辑能脱离 MongoDB 跑单测。game-web 提供 MongoDB 与内存两套实现，二者语义必须等价
 * （读写都返回副本、乐观锁版本比对），否则单测在内存实现上过了、上线在 Mongo 上炸。
 */
public interface CityRepository {

    /** 取某玩家的城建存档。新号返回 empty，由应用层负责初始化。 */
    Optional<CityState> findByPlayerId(String playerId);

    /**
     * 首次创建城建存档。
     *
     * <p>必须由存储层的唯一约束保证原子性（playerId 唯一索引），
     * 不能用「先查后插」—— 并发首次登录会插出两份存档。
     *
     * @return true 表示本次真的插入了；false 表示已存在
     */
    boolean insertIfAbsent(String playerId, CityState state);

    /**
     * 保存修改。
     *
     * @param playerId 玩家 id
     * @param state    城建存档
     * @param version  读取时的版本号，用于乐观锁比对
     * @return 保存后的新版本号
     * @throws IllegalStateException 版本不匹配（有并发写入），调用方应重读后重试
     */
    long save(String playerId, CityState state, long version);

    /** 读取当前版本号。 */
    long versionOf(String playerId);
}
