package com.ironoath.web.battle;

import org.springframework.stereotype.Component;

import com.ironoath.battle.TechBonus;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.tech.TechEffects;

/**
 * 职责：某个玩家<b>进乘区 B 的那一份科技加成</b> = 联盟科技 + 个人科技（B20 验收 3「四个战斗装配点全传」）。
 * 依赖：{@link AllianceTechBonuses}（联盟账本 → 定点）、{@link TechEffects}（个人账本 → 定点）、
 * {@link PlayerRepository}（读 {@code PlayerSave.tech()}）。
 *
 * <p><b>为什么要多这一个类，而不是在装配点各写一次 {@code alliance.plus(personal)}</b>：
 * 装配点有六处（野怪 / 攻城两方 / 采集战两方 / 关卡），两处来源各记一次就意味着
 * <b>漏一处的症状是"这场战斗没吃到个人科技"，而战斗不会报错、战报也看不出异常</b>。
 * 收成一口之后，装配点只要拿到一个 {@link TechBonus}，加法口径与"谁和谁相加"只有一个家。
 *
 * <p><b>相加而不是相乘</b>由 {@link TechBonus#plus} 落实（§五④ 的同类相加）。
 * 这里不改 {@link AllianceTechBonuses} 的语义：它仍然只管联盟那一份，
 * 所以它的既有测试与「无联盟就是 0」那条断言都还在原处。
 *
 * <p><b>集结战按发起者算</b>：这条口径继承 {@code AllianceTechBonuses} 类注释里 2026-09-13 的裁决
 * （{@code ArmySide} 只有一份科技加成），个人科技同理 —— 队友的科技在这场战斗里不生效。
 * 改它的代价是把 {@code ArmySide} 改成单位级科技，收益只落在集结战力的少数场景，本批仍不做。
 */
@Component
public class BattleTechBonuses {

    private final AllianceTechBonuses allianceBonuses;
    private final TechEffects techEffects;
    private final PlayerRepository players;

    public BattleTechBonuses(AllianceTechBonuses allianceBonuses, TechEffects techEffects,
                             PlayerRepository players) {
        this.allianceBonuses = allianceBonuses;
        this.techEffects = techEffects;
        this.players = players;
    }

    /**
     * 这个玩家的乘区 B 总加成。读不到存档（Bot 未建档、战报重建时的历史玩家）时退回联盟那一份，
     * 而不是抛 —— 战斗装配跑在读路径上，而"少一份个人科技"比"这场仗打不起来"更接近事实。
     */
    public TechBonus forPlayer(String playerId) {
        TechBonus alliance = allianceBonuses.forPlayer(playerId);
        if (playerId == null || playerId.isBlank()) {
            return alliance;
        }
        PlayerSave save = players.findByPlayerId(playerId).orElse(null);
        if (save == null) {
            return alliance;
        }
        TechBonus personal = new TechBonus(techEffects.attackPercent(save.tech()),
                techEffects.defensePercent(save.tech()));
        return alliance.plus(personal);
    }
}
