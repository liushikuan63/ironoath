package com.ironoath.core.army;

import java.util.Optional;

/**
 * 职责：军队存档的仓储端口（依赖倒置）。
 * 依赖：无。
 *
 * <p>与 {@code CityRepository}/{@code HeroRepository} 同一套约定：
 * 端口定义在 game-core，实现放在 game-web（内存版服务 dev/test，MongoDB 版由 B16 补）。
 * 于是「取消训练返还多少」「伤兵超容量死多少」这些规则可以脱离数据库跑单测。
 *
 * <p>乐观锁版本号是必需的：玩家锁只在单实例内有效，
 * 多实例部署时唯一能拦住并发覆盖的就是版本号。
 */
public interface ArmyRepository {

    Optional<ArmyState> findByPlayerId(String playerId);

    /** 原子插入：已存在则返回 false，不覆盖。 */
    boolean insertIfAbsent(String playerId, ArmyState army);

    /**
     * 带乐观锁落库。
     *
     * @return 落库后的新版本号
     * @throws IllegalStateException 版本不匹配（说明有并发写覆盖），调用方必须重读重试
     */
    long save(String playerId, ArmyState army, long expectedVersion);

    long versionOf(String playerId);
}
