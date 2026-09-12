package com.ironoath.core.player;

import com.ironoath.core.season.SeasonTier;

import java.util.List;

/**
 * 职责：玩家主存档上唯一保留的赛季数据 —— 荣耀三件套（B14 §4：赛季数据不进主表，
 * 只把「荣耀等级 / 历史最高段位 / 赛季徽章」这三项抄回主存档）。
 * 依赖：{@link SeasonTier}（只借它的档位枚举，不借它的规则与门槛）。
 *
 * <p><b>它是派生缓存，不是第二份真相</b>：真相在赛季结算账本（每一季一条记录），
 * 这三项随时可以由账本重算出来。主存档带一份的理由只有一个 —— 面板与登录路径不该为了
 * 显示「我打过几季」去逐季点查账本。<b>一旦两者不一致，以账本为准并就地修一次</b>
 * （见 {@code SeasonAppService#gloryOf} 的读路径）。
 *
 * <p><b>为什么类型只有这一个</b>：同一个「荣耀三件套」此前在 core 与 web 各有一个记录类型
 * （{@code SeasonSettlement.GloryRecord} 与 {@code SeasonLedgerStore.Glory}），字段一样、
 * 转换靠手写。两个类型不会同时错，但会让人搞不清哪个是要存的那一份 —— 所以合并成这一个。
 */
public record PlayerGlory(int gloryLevel, SeasonTier.Tier highestTier, List<String> badges) {

    public PlayerGlory {
        if (gloryLevel < 0) {
            throw new IllegalArgumentException("gloryLevel 不得为负，实际=" + gloryLevel);
        }
        if (highestTier == null) {
            throw new IllegalArgumentException("highestTier 不得为 null：没打过的人应当是 BRONZE 而不是缺值");
        }
        badges = List.copyOf(badges == null ? List.of() : badges);
    }

    /** 从没打过赛季。老存档没这个字段时也是它（与 {@code PlayerPvp.empty()} 同一条读法）。 */
    public static PlayerGlory empty() {
        return new PlayerGlory(0, SeasonTier.Tier.BRONZE, List.of());
    }

    /** 一次都没结算过 —— 读路径用它判断「缓存是不是还没长出来」。 */
    public boolean isBlank() {
        return gloryLevel == 0 && badges.isEmpty();
    }
}
