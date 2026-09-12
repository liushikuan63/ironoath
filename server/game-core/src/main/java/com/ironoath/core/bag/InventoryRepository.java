package com.ironoath.core.bag;

import java.util.Optional;

/**
 * 职责：背包仓储端口（六边形架构的抽象侧）。
 * 依赖：无（纯接口）。
 *
 * <p>与 {@link com.ironoath.core.player.PlayerRepository}、
 * {@link com.ironoath.core.city.CityRepository} 同一套约定：
 * 定义在 game-core 让背包逻辑能脱离 MongoDB 跑单测，game-web 提供内存与 Mongo 两套实现，
 * 二者语义必须等价（读写都返回副本、乐观锁版本比对），
 * 否则单测在内存实现上过了、上线在 Mongo 上炸 —— 而这种 bug 只在并发时出现。
 */
public interface InventoryRepository {

    /** 取背包。新号返回 empty，由应用层用配置的初始容量创建。 */
    Optional<Inventory> findByPlayerId(String playerId);

    /**
     * 首次创建背包。
     *
     * <p>必须由存储层的唯一约束保证原子性（playerId 唯一索引），
     * 不能用「先查后插」—— 并发首次登录会插出两份背包，道具会随机落在其中一份上。
     *
     * @return true 表示本次真的插入了；false 表示已存在
     */
    boolean insertIfAbsent(String playerId, Inventory inventory);

    /**
     * 保存修改。
     *
     * @param expectedVersion 读取时的版本号，用于乐观锁比对
     * @return 保存后的新版本号
     * @throws IllegalStateException 版本不匹配（有并发写入），调用方应重读后重试
     */
    long save(String playerId, Inventory inventory, long expectedVersion);

    long versionOf(String playerId);
}
