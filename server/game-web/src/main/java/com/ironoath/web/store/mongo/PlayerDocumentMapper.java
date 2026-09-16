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
                save.version());
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

        PlayerSave save = new PlayerSave();
        save.restore(doc.playerId(), doc.deviceId(), doc.nickName(), doc.avatarId(),
                doc.createdAt(), doc.lastLoginAt(), doc.cityLevel(), resources,
                power, pvp, doc.protectUntil(), glory, guide, doc.version());
        return save;
    }
}
