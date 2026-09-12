package com.ironoath.core.gacha;

import java.util.List;

/**
 * 职责：抽卡日志端口（B06 §6 合规要求，验收 4）。
 * 依赖：无。
 *
 * <p><b>这不是普通的业务日志，是合规凭证</b>：B06 §6 要求「每次抽卡写日志：
 * 玩家 id / 时间 / 池子 / 结果 / 是否保底，保留 90 天」，
 * 禁止项里两次强调「抽卡日志不要只存结果不存是否保底」「不要缺 isPity 字段（监管会查）」。
 * 所以它是端口而不是 {@code LOG.info}：
 * <ul>
 *   <li>文本日志无法按玩家与时间检索，监管来查时拿不出结构化结果</li>
 *   <li>文本日志的保留期由日志轮转策略决定，而不是由
 *       {@code global.GACHA_LOG_RETENTION_DAYS} 决定，两者必然漂移</li>
 *   <li>字段漏一个（尤其是 isPity）在文本日志里没人会发现，在结构化写入里编译期就会报错</li>
 * </ul>
 *
 * <p>实现放在 game-web：内存版用于 dev/test，MongoDB 版必须带
 * {@code (playerId, drawnAt)} 复合索引与按保留期的清理，见 B16。
 */
public interface GachaLogStore {

    /**
     * 一条抽卡日志。
     *
     * @param playerId  玩家 id
     * @param poolId    卡池 id
     * @param drawnAt   服务端时间戳（毫秒）
     * @param requestId 幂等标识，用于把一次十连的 10 条记录关联起来
     * @param seed      本次抽取的种子，配合 count 可完整复现结果（B06 验收 11）
     * @param drawIndex 本次是这批里的第几抽（从 0 开始）
     * @param heroId    抽到的武将
     * @param tier      稀有度档位
     * @param isPity    <b>是否由保底触发</b>。合规必需字段，缺失即视为不合规
     * @param isNew     是否首次获得（false 表示重复，已转碎片）
     * @param fragments 本次转化的碎片数
     */
    record Entry(String playerId, String poolId, long drawnAt, String requestId,
                 long seed, int drawIndex, String heroId, Tier tier,
                 boolean isPity, boolean isNew, long fragments) {

        public Entry {
            if (playerId == null || playerId.isBlank()) {
                throw new IllegalArgumentException("playerId 不得为空");
            }
            if (poolId == null || poolId.isBlank()) {
                throw new IllegalArgumentException("poolId 不得为空");
            }
            if (drawnAt <= 0L) {
                throw new IllegalArgumentException("drawnAt 必须为正的服务端时间戳，实际=" + drawnAt);
            }
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("requestId 不得为空：一次十连的 10 条记录靠它关联");
            }
            if (drawIndex < 0) {
                throw new IllegalArgumentException("drawIndex 不得为负：" + drawIndex);
            }
            if (heroId == null || heroId.isBlank()) {
                throw new IllegalArgumentException("heroId 不得为空");
            }
            if (tier == null) {
                throw new IllegalArgumentException("tier 不得为 null");
            }
            if (fragments < 0L) {
                throw new IllegalArgumentException("fragments 不得为负：" + fragments);
            }
        }
    }

    /** 写入一批（一次单抽/十连的全部记录）。实现必须保证「要么全写要么全不写」。 */
    void appendAll(List<Entry> entries);

    /**
     * 查某玩家某时间之后的日志，按时间升序。
     *
     * @param sinceMillis 起始时间戳（含）
     */
    List<Entry> query(String playerId, long sinceMillis);

    /** 删除早于给定时间戳的日志。保留期由 global.GACHA_LOG_RETENTION_DAYS 决定。 */
    int purgeBefore(long cutoffMillis);
}
