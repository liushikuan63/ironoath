package com.ironoath.web.store.mongo;

import com.ironoath.core.player.PlayerGlory;
import com.ironoath.core.player.PlayerGuide;
import com.ironoath.core.player.PlayerPower;
import com.ironoath.core.player.PlayerPvp;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：玩家领域模型 ↔ MongoDB 文档的双向转换。
 * 依赖：game-core 的 PlayerSave、本包的 PlayerDocument。
 *
 * <p>转换集中在这一个类里，是为了让「文档加了字段但领域模型忘了同步」这类问题
 * 只可能出现在一处，而不是散落在十几个 service 里。
 */
public final class PlayerDocumentMapper {

    private PlayerDocumentMapper() {
    }

    public static PlayerDocument toDocument(PlayerSave save) {
        Map<String, PlayerDocument.ResourceDoc> resources = new LinkedHashMap<>();
        save.resources().forEach((type, state) -> resources.put(type,
                new PlayerDocument.ResourceDoc(
                        state.current(), state.cap(), state.protectedAmount(),
                        state.perHour(), state.lastSettle())));

        PlayerPower power = save.power();
        PlayerPvp pvp = save.pvp();
        return new PlayerDocument(
                save.playerId(),
                save.deviceId(),
                save.nickName(),
                save.avatarId(),
                save.createdAt(),
                save.lastLoginAt(),
                save.cityLevel(),
                resources,
                new PlayerDocument.PowerDoc(power.displayPower(), power.matchPower(), power.peakPower()),
                new PlayerDocument.PvpDoc(pvp.peakTouchedAt(), pvp.tyranny(), pvp.tyrannyTouchedAt(),
                        pvp.victimShieldUntil(), pvp.attackerHits(),
                        pvp.peaceUntil(), pvp.closedUntil(), pvp.exileAt()),
                save.protectUntil(),
                new PlayerDocument.GloryDoc(save.glory().gloryLevel(),
                        save.glory().highestTier().name(), save.glory().badges()),
                // 与 GloryDoc 一样无条件写：没走过引导也是一个明确的值（stepIndex=0、finishedAt=null），
                // 而不是"这一列不存在" —— 后者会让读路径分不清"老号"与"这条更新漏写了"
                new PlayerDocument.GuideDoc(save.guide().stepIndex(), save.guide().finishedAt()),
                // 同上：一位不漏地写。付费权益里"什么都没买过"是一个明确的值，
                // 让这一列缺席就等于把"老号"与"这次更新漏写了付费状态"混成同一种读数 ——
                // 后者的症状是玩家的月卡凭空消失
                toPaidDoc(save.paid()),
                // 同上：一行都没研究、队列空着，也是一个明确写下来的值而不是缺列
                toTechDoc(save.tech()),
                // 同上：一次都没弹、没触发过，也是一个明确写下来的值而不是缺列（S3-ii）
                toGiftPopupDoc(save.giftPopup()),
                save.version());
    }

    private static PlayerDocument.TechDoc toTechDoc(com.ironoath.core.player.PlayerTech tech) {
        return new PlayerDocument.TechDoc(new java.util.LinkedHashMap<>(tech.levels()),
                tech.researchingId(), tech.finishAt(), tech.startedAt(), tech.totalSeconds());
    }

    private static PlayerDocument.PaidDoc toPaidDoc(com.ironoath.core.player.PlayerPaid paid) {
        return new PlayerDocument.PaidDoc(paid.cardExpireAt(), paid.cardClaimedThroughAt(),
                paid.fundPurchasedAt(), java.util.List.copyOf(paid.fundClaimedTiers()),
                paid.firstChargedAt(), java.util.List.copyOf(paid.fulfilledOrderIds()));
    }

    public static PlayerSave toDomain(PlayerDocument doc) {
        Map<String, PlayerResourceState> resources = new LinkedHashMap<>();
        if (doc.resources() != null) {
            doc.resources().forEach((type, r) -> resources.put(type,
                    new PlayerResourceState(r.current(), r.cap(), r.protectedAmount(),
                            r.perHour(), r.lastSettle())));
        }
        PlayerDocument.PowerDoc p = doc.power();
        PlayerPower power = p == null
                ? PlayerPower.zero()
                : new PlayerPower(p.displayPower(), p.matchPower(), p.peakPower());
        PlayerDocument.PvpDoc v = doc.pvp();
        // 老存档没有 pvp 子文档：补空状态。B08 之前建的号本来就没有暴虐值与护盾
        PlayerPvp pvp = v == null
                ? PlayerPvp.empty()
                : new PlayerPvp(v.peakTouchedAt(), v.tyranny(), v.tyrannyTouchedAt(),
                        v.victimShieldUntil(), v.attackerHits(), v.peaceUntil(), v.closedUntil(),
                        v.exileAt());

        // 荣耀缓存：缺失或段位名字认不出来（改过档名、脏数据）都补 empty 而不是抛 ——
        // 它是派生缓存，读不懂随时能由账本重算，代价一次自愈；
        // 而把它变成异常等于让一个缓存字段挡住登录
        PlayerDocument.GloryDoc g = doc.glory();
        com.ironoath.core.player.PlayerGlory glory = PlayerGlory.empty();
        if (g != null && g.highestTier() != null) {
            try {
                glory = new PlayerGlory(g.gloryLevel(),
                        com.ironoath.core.season.SeasonTier.Tier.valueOf(g.highestTier()), g.badges());
            } catch (IllegalArgumentException e) {
                // 落到上面的 empty：认不出来的段位不值得让存档读不出来
            }
        }

        // 引导进度：缺子文档（老号）与子文档本身是脏的（负序号）都读成"从未开始" ——
        // 与荣耀缓存同一条纪律：这一位读不懂的代价是引导重弹一次，而不是登录失败
        PlayerDocument.GuideDoc d = doc.guide();
        PlayerGuide guide = PlayerGuide.empty();
        if (d != null && d.stepIndex() >= 0) {
            guide = new PlayerGuide(d.stepIndex(), d.finishedAt());
        }

        // 付费权益：缺子文档 = B19 之前的老号，读成「什么都没买过」。
        // 但**子文档存在而内容读不懂时必须抛**，不像上面的荣耀与引导那样静默补 empty ——
        // 那两位是派生缓存，读不懂随时能重算；这一位是付过钱的账，悄悄读成 empty 的症状是
        // 「玩家的月卡凭空消失」，而它不会自己长回来。宁可让这一次登录炸出来。
        PlayerDocument.PaidDoc pd = doc.paid();
        com.ironoath.core.player.PlayerPaid paid = pd == null
                ? com.ironoath.core.player.PlayerPaid.empty()
                : new com.ironoath.core.player.PlayerPaid(pd.cardExpireAt(), pd.cardClaimedThroughAt(),
                        pd.fundPurchasedAt(), safeList(pd.fundClaimedTiers()), pd.firstChargedAt(),
                        safeList(pd.fulfilledOrderIds()));

        // 个人科技：缺子文档 = B20 之前的老号，读成「一行都没研究」。
        // 与付费同一条纪律：**这一位不是派生缓存**，读不懂时抛而不是补 empty ——
        // 悄悄把一个在研究中的槽读成空闲，等于把玩家已经等掉的那段时间扔掉，而它不会自己长回来。
        // 唯一的宽容是 levels 里的 0 或负数：那与"这行没研究过"是同一件事，去掉占位即可。
        PlayerDocument.TechDoc td = doc.tech();
        com.ironoath.core.player.PlayerTech tech = com.ironoath.core.player.PlayerTech.empty();
        if (td != null) {
            Map<String, Integer> levels = new LinkedHashMap<>();
            if (td.levels() != null) {
                td.levels().forEach((id, level) -> {
                    if (level != null && level > 0) {
                        levels.put(id, level);
                    }
                });
            }
            String researching = td.researchingId() == null || td.researchingId().isBlank()
                    ? null : td.researchingId();
            tech = new com.ironoath.core.player.PlayerTech(levels, researching,
                    researching == null ? null : td.finishAt(),
                    researching == null ? 0L : td.startedAt(),
                    researching == null ? 0L : td.totalSeconds());
        }

        // 礼包弹窗：缺子文档 = S3-ii 之前的老号，读成「一次都没弹、也没触发过」。
        // 这一位读不懂也不要紧：丢的最多是一次弹窗记账（重启后多弹一次），不是玩家资产 ——
        // 与付费/科技那两位「读不懂就抛」的纪律不同档，所以这里全程容 null。
        PlayerDocument.GiftPopupDoc gd = doc.giftPopup();
        com.ironoath.core.player.PlayerGiftPopup giftPopup = gd == null
                ? com.ironoath.core.player.PlayerGiftPopup.empty()
                : new com.ironoath.core.player.PlayerGiftPopup(gd.lastShowAt(),
                        gd.showsByGift() == null ? java.util.Map.of() : gd.showsByGift(),
                        gd.triggeredAt() == null ? java.util.Map.of() : gd.triggeredAt(),
                        gd.purchaseDayKey(),
                        gd.purchasedCountByGift() == null ? java.util.Map.of() : gd.purchasedCountByGift());

        PlayerSave save = new PlayerSave();
        save.restore(doc.playerId(), doc.deviceId(), doc.nickName(), doc.avatarId(),
                doc.createdAt(), doc.lastLoginAt(), doc.cityLevel(), resources,
                power, pvp, doc.protectUntil(), glory, guide, paid, tech, giftPopup, doc.version());
        return save;
    }

    /** 存档那位 -> 文档子结构。一位不漏地写，见 {@link PlayerDocument.GiftPopupDoc} 的读法说明。 */
    private static PlayerDocument.GiftPopupDoc toGiftPopupDoc(
            com.ironoath.core.player.PlayerGiftPopup popup) {
        return new PlayerDocument.GiftPopupDoc(popup.lastShowAt(), popup.showsByGift(), popup.triggeredAt(),
                popup.purchaseDayKey(), popup.purchasedCountByGift());
    }

    /** 弹出时刻表：整列缺席（老文档）读成空表，单个礼包的列表缺席也照样跳过而不是抛。 */
    private static java.util.Map<String, java.util.List<Long>> safeShows(
            java.util.Map<String, java.util.List<Long>> source) {
        if (source == null || source.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, java.util.List<Long>> copy = new java.util.LinkedHashMap<>();
        source.forEach((giftId, times) -> {
            if (giftId != null && times != null && !times.isEmpty()) {
                copy.put(giftId, java.util.List.copyOf(times));
            }
        });
        return copy;
    }

    /** null 容忍：老文档里这两个列表可以整个缺席（{@code PlayerPaid} 自己会把 null 读成空集）。 */
    private static java.util.Set<String> safeList(java.util.List<String> values) {
        // LinkedHashSet 而不是 Set.copyOf：文档里存的是有序 List，回来也要保住顺序，
        // 否则发货幂等账本"丢最早几笔"的淘汰顺序就变了
        return values == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(values);
    }
}
