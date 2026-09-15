package com.ironoath.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ironoath.battle.TechBonus;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.web.battle.AllianceTechBonuses;
import com.ironoath.web.dto.generated.AllianceCreateReq;
import com.ironoath.web.dto.generated.AllianceDonateReq;
import com.ironoath.web.dto.generated.AllianceTechReq;
import com.ironoath.web.dto.generated.PlayerInitReq;
import com.ironoath.web.service.PlayerInitService;
import com.ironoath.web.service.SocialAppService;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：联盟科技 → 战斗乘区 B 这条接线的验证（B05 §1.3 的乘区 B、B10 §2 的「全盟生效」）。
 * 依赖：Spring Boot Test；test profile（内存存储）。
 *
 * <p><b>为什么单独测这一段</b>：内核要的是「一份攻击加成 + 一份防御加成」的 {@link TechBonus}，
 * 而联盟账本记的是「每条科技各到哪一级」。中间这次折算最容易出两种错，
 * 且两种都不会让任何一场战斗报错：把别的属性也乘进去（行军速度变成了攻击力），
 * 或把 +1.5% 实现成 ×1.5。所以这里断的是<b>精确的定点值</b>，不是「大于 0」。
 */
@SpringBootTest
@ActiveProfiles("test")
class AllianceTechBonusTest {

    @Autowired private AllianceTechBonuses techBonuses;
    @Autowired private SocialAppService social;
    @Autowired private PlayerInitService playerInitService;
    @Autowired private PlayerRepository players;
    @Autowired private SocialStore socialStore;

    /** 没有联盟就没有科技：打野与 PVE 不该被联盟绑住，所以要回 0 而不是抛错。 */
    @Test
    @DisplayName("无联盟的玩家：乘区 B 是 0，而不是「缺状态」异常")
    void playerWithoutAllianceGetsZeroBonus() {
        String lonely = newPlayer();

        assertThat(techBonuses.forPlayer(lonely)).isEqualTo(TechBonus.none());
        assertThat(techBonuses.forPlayer(null))
                .as("null 玩家 id 也不能炸（战报重建与 Bot 战斗都会走到这条路）")
                .isEqualTo(TechBonus.none());
    }

    /**
     * 端到端：建盟 → 攒公账 → 研究攻防科技 → 乘区 B 出现精确的定点值。
     *
     * <p>{@code alliance_tech} 表里 atk / def 的单级幅度都是 1.5%（定点 150），
     * 所以 1 级 ⇒ 150、2 级 ⇒ 300。<b>断死数字</b>是因为「+1.5% 实现成 ×1.5」这种错法
     * 只会让战斗变怪而不会报错，而那正是必须在测试里拦住的形状。
     */
    @Test
    @DisplayName("研究联盟攻防科技后，攻击与防御各自拿到精确的定点值")
    void researchedTechLandsInZoneBWithExactFixedPointValues() {
        String leader = newPlayer();
        social.allianceCreate(leader, new AllianceCreateReq(reqId(), "加成盟", "BONUS"));
        donateFreeAndGoldTiers(leader);
        topUpFund(leader, 6000L);
        assertThat(techBonuses.forPlayer(leader)).as("研究之前必须是 0").isEqualTo(TechBonus.none());

        social.allianceTech(leader, new AllianceTechReq(reqId(), "atech_atk", 1));
        social.allianceTech(leader, new AllianceTechReq(reqId(), "atech_def", 2));

        TechBonus bonus = techBonuses.forPlayer(leader);
        assertThat(bonus.attackFixed()).as("攻击 1 级 = 单级幅度 150（定点）").isEqualTo(150L);
        assertThat(bonus.defenseFixed()).as("防御 2 级 = 150 × 2").isEqualTo(300L);
    }

    /** 只有攻击与防御两个属性有消费方；其余六个的接法要等口径裁决（见 {@link AllianceTechBonuses} 类注释）。 */
    @Test
    @DisplayName("非攻防属性的科技不会污染乘区 B，但账仍然照记")
    void otherEffectAttributesDoNotLeakIntoZoneB() {
        String leader = newPlayer();
        social.allianceCreate(leader, new AllianceCreateReq(reqId(), "泄漏盟", "LEAK"));
        donateFreeAndGoldTiers(leader);
        topUpFund(leader, 6000L);

        social.allianceTech(leader, new AllianceTechReq(reqId(), "atech_march", 1));

        TechBonus bonus = techBonuses.forPlayer(leader);
        assertThat(bonus.attackFixed()).as("行军速度不能被乘进攻击乘区：那样数值追溯就废了").isZero();
        assertThat(bonus.defenseFixed()).isZero();
        assertThat(socialStore.allianceOf(leader).orElseThrow().techLevel("atech_march"))
                .as("不生效不等于不记账 —— 偷偷不记会让以后接线时没人知道已经研究到哪一级")
                .isEqualTo(1);
    }

    // ---------- 夹具 ----------

    /** 主城 10 级、金币备足（建盟要 500）的新号。 */
    private String newPlayer() {
        String playerId = playerInitService.init(new PlayerInitReq(
                reqId(), "dev-" + UUID.randomUUID(), "科技接线", 1_700_000_000_000L, "")).playerId();
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        save.setCityLevel(10);
        PlayerResourceState gold = save.resources().get("GOLD");
        if (gold != null) {
            save.putResource("GOLD", new PlayerResourceState(100_000L, gold.cap(),
                    gold.protectedAmount(), gold.perHour(), gold.lastSettle()));
        }
        players.save(save);
        return playerId;
    }

    /**
     * 攒公账的夹具。
     *
     * <p>原先这里写的是「同一档金币捐三次」。2026-09-13 裁决把口径定成
     * <b>每档每日一次</b>（收口清单 §三·补 A1）之后，那条路正好是新语义要拦的形状 ——
     * 同一档刷满 = 零成本把贡献值（联盟商店的货币）捞到手。
     *
     * <p>所以这里捐免费档与金币档各一次（<b>捐献这条真路径仍然被走一遍</b>，
     * 扣款与记账都在用例眼前发生），差额用 {@code addFund} 补：它是"活动奖励、领地收入"
     * 这类合法外部来源共用的入口，比假装一天能捐 7500 诚实。
     */
    private void donateFreeAndGoldTiers(String leader) {
        social.allianceDonate(leader, new AllianceDonateReq(reqId(), 0));
        social.allianceDonate(leader, new AllianceDonateReq(reqId(), 2));
    }

    private void topUpFund(String leader, long amount) {
        com.ironoath.core.social.Alliance alliance = socialStore.allianceOf(leader).orElseThrow();
        // 版本号必须在改动之前取：addFund 自己会 bumpVersion，
        // 拿改完的 version 当 expectedVersion 等于告诉存储"我认为上一版是这个数"——必撞 CAS
        long expectedVersion = alliance.version();
        alliance.addFund(amount);
        socialStore.saveAlliance(alliance, expectedVersion);
    }

    private static String reqId() {
        return "req-" + UUID.randomUUID();
    }
}
