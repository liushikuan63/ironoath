package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

import java.util.Map;

/**
 * 职责：玩家存档的 MongoDB 文档模型（持久化层专用，与领域模型解耦）。
 * 依赖：无（纯数据 + Spring Data 注解）。
 *
 * <p>为什么不直接把 game-core 的 {@code PlayerSave} 存进去：领域模型会随玩法批次频繁演进
 * （B03 加建筑、B04 加背包、B06 加武将），如果它就是存储模型，每次改字段都要考虑历史数据迁移。
 * 分开之后，文档结构可以按存储需要独立演进（比如把资源拆成子文档、给热字段建索引），
 * 领域模型则保持业务表达的清晰。转换集中在 {@link PlayerDocumentMapper}。
 *
 * @param playerId      主键
 * @param deviceId      设备 id，<b>唯一索引</b>，是「同设备只建一个号」的保证
 * @param version       乐观锁版本号
 */
public record PlayerDocument(
        @Id String playerId,
        String deviceId,
        String nickName,
        int avatarId,
        long createdAt,
        long lastLoginAt,
        int cityLevel,
        Map<String, ResourceDoc> resources,
        PowerDoc power,
        PvpDoc pvp,
        Long protectUntil,
        GloryDoc glory,
        GuideDoc guide,
        long version) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "player";

    /**
     * 新手引导进度的持久化形态（B18：存档上的两位 —— 当前步序号与结束时刻）。
     *
     * <p><b>可空</b>：B18 之前建的号没有这一位，读回来补 {@code PlayerGuide.empty()}（从未开始）
     * 而不是抛 —— 与 {@link GloryDoc}、{@link PvpDoc} 缺失时同一条读法。
     */
    public record GuideDoc(int stepIndex, Long finishedAt) {
    }

    /**
     * 荣耀三件套的持久化形态（B14 §4：唯一允许留在主存档的赛季数据）。
     *
     * <p><b>可空</b>：这轮之前的号压根没存过荣耀，读回来补 {@code PlayerGlory.empty()} 而不是抛 ——
     * 与 {@link PvpDoc} 缺失时同一条读法。
     *
     * <p>段位存<b>名字</b>而不是 ordinal：将来中间插一档（比如加个「星耀」）会让所有历史存档
     * 集体错位一格，而那种错不会报任何错。
     */
    public record GloryDoc(int gloryLevel, String highestTier, java.util.List<String> badges) {
    }

    /** 单一资源的持久化形态，字段与惰性结算模型一一对应。 */
    public record ResourceDoc(long current, long cap, long protectedAmount, long perHour, long lastSettle) {
    }

    /** 战力三元组的持久化形态。 */
    public record PowerDoc(long displayPower, long matchPower, long peakPower) {
    }

    /**
     * PVP 侧状态的持久化形态（B08）。
     *
     * <p>{@code attackerHits} 存成 map 而不是列表：受害护盾按<b>不同攻击者</b>计数，
     * 用列表就得每次去重，而 Mongo 的 map 天然去重且能按 key 局部更新。
     */
    public record PvpDoc(long peakTouchedAt,
                         long tyranny,
                         long tyrannyTouchedAt,
                         Long victimShieldUntil,
                         Map<String, Long> attackerHits,
                         Long peaceUntil,
                         Long closedUntil,
                         Long exileAt) {
        /** {@code exileAt} 缺失 = 从未流亡过（老存档本就没这个玩法），读成 null 而不是 0：0 会被当成「1970 年迁过一次」。 */
    }
}
