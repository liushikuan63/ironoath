package com.ironoath.core.stage;

import java.util.Optional;

/**
 * 职责：章节进度仓储端口（依赖倒置）。
 * 依赖：无。
 *
 * <p>与 CityRepository / ArmyRepository / HeroRepository 同一套约定：端口在 game-core，
 * 实现在 game-web（内存版服务 dev/test，MongoDB 版由 B16 补），
 * 于是「星级只升不降」这类规则可以脱离数据库跑单测。
 *
 * <p>乐观锁版本号是必需的：挑战与扫荡都是「读-改-写」，
 * 玩家锁只在单实例内有效，多实例部署时唯一能拦住并发覆盖的就是版本号。
 */
public interface StageProgressRepository {

    Optional<StageProgress> findByPlayerId(String playerId);

    /** 原子插入：已存在则返回 false，不覆盖。 */
    boolean insertIfAbsent(String playerId, StageProgress progress);

    /**
     * 带乐观锁落库。
     *
     * @throws IllegalStateException 版本不匹配（有并发写覆盖），调用方必须重读重试
     */
    long save(String playerId, StageProgress progress, long expectedVersion);

    long versionOf(String playerId);
}
