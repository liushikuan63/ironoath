package com.ironoath.core.march;

import java.util.List;
import java.util.Optional;

/**
 * 职责：行军存档的仓储端口。
 * 依赖：无。
 *
 * <p>与城建/武将/军队同一套约定：端口在 game-core，实现在 game-web
 * （内存版服务 dev/test，MongoDB 版由 B16 补）。
 *
 * <p>B07 §2 明写「持久化在 MongoDB」—— 行军是<b>跨会话</b>的长生命周期实体，
 * 玩家杀进程重进后所有队伍必须按真实剩余时间继续（验收 1）。
 * 内存实现只用于本地开发与单测，装配处会用 WARN 日志把这件事喊出来。
 */
public interface MarchRepository {

    Optional<March> findById(String marchId);

    /** 某玩家的全部行军（含各状态），按出发时刻升序。 */
    List<March> findByPlayerId(String playerId);

    /** 终点或起点落在给定 chunk 键集合内的行军，供 viewport 下发地图实体。 */
    List<March> findByChunkKeys(List<String> chunkKeys);

    /** 原子插入。id 冲突返回 false。 */
    boolean insertIfAbsent(March march);

    /**
     * 带乐观锁落库。
     *
     * @return 落库后的新版本号
     * @throws IllegalStateException 版本不匹配（有并发写覆盖），调用方必须重读重试
     */
    long save(March march, long expectedVersion);

    long versionOf(String marchId);

    void delete(String marchId);

    /** 某玩家当前在外（未到家）的行军数，用于校验 MARCH_MAX_CONCURRENT。 */
    long activeCountOf(String playerId);
}
