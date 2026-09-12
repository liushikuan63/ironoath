package com.ironoath.core.hero;

import java.util.Optional;

/**
 * 职责：武将存档的仓储端口（依赖倒置）。
 * 依赖：无。
 *
 * <p>与 {@code CityRepository}/{@code InventoryRepository} 同一套约定：
 * 端口定义在 game-core，实现放在 game-web（内存版用于 dev/test，MongoDB 版用于 prod）。
 * 于是武将逻辑可以脱离数据库跑单测。
 *
 * <p><b>乐观锁版本号是必需的</b>，不是可选的加固：武将养成是一串「读-改-写」，
 * 玩家锁只在单实例内有效（JVM 锁），多实例部署时唯一能拦住并发覆盖的就是版本号。
 */
public interface HeroRepository {

    Optional<HeroRoster> findByPlayerId(String playerId);

    /** 原子插入：已存在则返回 false，不覆盖。 */
    boolean insertIfAbsent(String playerId, HeroRoster roster);

    /**
     * 带乐观锁落库。
     *
     * @param expectedVersion 读取时拿到的版本号
     * @return 落库后的新版本号
     * @throws IllegalStateException 版本不匹配（说明有并发写覆盖），调用方必须重读重试
     */
    long save(String playerId, HeroRoster roster, long expectedVersion);

    long versionOf(String playerId);
}
