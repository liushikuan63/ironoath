package com.ironoath.core.player;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 职责：玩家存档仓储端口（六边形架构的抽象侧）。
 * 依赖：无（纯接口）。
 *
 * <p>定义在 game-core 而非 game-web，是为了让玩法逻辑可以脱离 MongoDB 跑单测：
 * game-web 提供 MongoDB 实现，单测提供内存实现，两者对玩法代码完全等价。
 *
 * <p>并发约定（B00 Java 五大技术陷阱第 3 条）：禁止「先查后改」扣资源。
 * 写入必须走分布式锁 + 事务 + {@code requestId} 幂等，具体由实现类保证；
 * {@link #save} 采用乐观锁（{@link PlayerSave#version()}），版本不匹配即抛异常让上层重试。
 */
public interface PlayerRepository {

    /** 按玩家 id 查存档。 */
    Optional<PlayerSave> findByPlayerId(String playerId);

    /** 按设备 id 查存档。同一设备重复 init 必须返回同一份存档（B01 验收 11）。 */
    Optional<PlayerSave> findByDeviceId(String deviceId);

    /**
     * 批量按玩家 id 查存档，返回 playerId -> 存档。
     *
     * <p>B08 的目标搜索要读入半径内的全部候选（昵称、活跃时刻、护盾、库存富度、匹配战力），
     * 逐个 {@link #findByPlayerId} 在候选池上千时就是上千次查询 ——
     * 一次搜索请求打出上千次存储往返是不能接受的，而这件事只有批量端口能在存储层解决。
     *
     * <p><b>约定</b>：查不到的 id 直接不出现在结果里，不返回 null 值、不抛异常。
     * 候选池来自世界仓储的城位置，而删号不会同步清理坐标（那是 B14 赛季重置的事），
     * 所以「有城没人」是一个正常状态，调用方必须自己处理缺失。
     *
     * @param playerIds 要查的玩家 id；null 或空集合返回空 map
     */
    Map<String, PlayerSave> findByPlayerIds(Collection<String> playerIds);

    /**
     * 原子插入：仅当 deviceId 尚未存在时才写入。
     *
     * <p>必须由存储层的唯一索引保证原子性，不能用「先查后插」——
     * 两个并发请求会同时查到不存在，然后都插入，产生一个设备两份存档。
     *
     * @return true 表示本次真的插入了；false 表示已存在（调用方应改为读取现有存档）
     */
    boolean insertIfAbsent(PlayerSave save);

    /**
     * 保存修改。
     *
     * @throws IllegalStateException 当乐观锁版本不匹配（说明有并发写入），调用方应重读后重试
     */
    void save(PlayerSave save);

    /**
     * 定向更新登录时间戳 —— <b>不做版本校验</b>。
     *
     * <p>为什么不走 {@link #save}：{@code lastLoginAt} 是单调、可交换的字段，
     * 并发登录时「读-改-写 + 乐观锁」必然让其中一方冲突失败，玩家看到的是「登录失败」。
     * 而登录失败是最不该出现的错误 —— 它发生在玩家刚点开游戏的第一秒。
     * 乐观锁应当只保护玩法状态（资源、建筑、兵力），不该保护一个时间戳。
     *
     * <p>实现要求：用存储层的条件更新保证单调（如 MongoDB 的
     * {@code filter: lastLoginAt < now, update: $set lastLoginAt = now}），
     * 不要先读出来再写回。存档不存在时静默忽略（可能刚被删号）。
     *
     * @param playerId 玩家 id
     * @param now      服务端当前时间戳
     */
    void touchLogin(String playerId, long now);
}
