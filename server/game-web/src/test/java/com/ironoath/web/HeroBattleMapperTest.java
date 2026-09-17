package com.ironoath.web;

import com.ironoath.battle.HeroSnapshot;
import com.ironoath.battle.SkillSnapshot;
import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.SkillCfg;
import com.ironoath.core.hero.HeroAttrs;
import com.ironoath.core.hero.HeroCalculator;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.web.battle.HeroBattleMapper;
import com.ironoath.web.service.HeroStatsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：验证武将状态到战斗快照的映射 —— 尤其是「技能等级终于有效果了」这件事。
 * 依赖：Spring Boot Test（要真实的 hero / skill 表）；test profile。
 *
 * <p><b>本类钉住的是一个曾经完全失效的付费点</b>：B06 交付了 skillUp（消耗道具、有等级上限、
 * 面板显示等级），但技能等级不进任何公式 —— 玩家花道具升级，收益精确为 0。
 * 这类缺陷不会让任何测试变红（升级本身是成功的：等级确实 +1、道具确实扣了、面板确实更新了），
 * 只会在玩家社区里变成一句「技能升级是骗钱的」。所以这里必须有一条断言直接说
 * 「10 级的技能数值就是比 1 级高」，而不是只断言「映射没抛异常」。
 *
 * <p>反过来，<b>触发概率与持续回合必须不随等级变</b>，同样要有断言：
 * 那两项一旦跟着等级涨，战斗节奏就会随等级剧烈变化，
 * 而 B05 的回合数、平局判定、buff 临时性全是按固定节奏校准的。
 */
@SpringBootTest
@ActiveProfiles("test")
class HeroBattleMapperTest {

    private static final String HERO = "hero_ssr_02";
    /** 一个没有背包的玩家 id。本类测的是"技能等级与属性进不进战斗快照"，装备这一族必须是空的：
     *  借用共享存储里恰好存在的装备，症状就是某天气值差对不上而没人能复现 */
    private static final String PLAYER = "p-battle-mapper-test";

    @Autowired private HeroBattleMapper mapper;
    @Autowired private HeroStatsService heroStats;
    @Autowired private ConfigRegistry configs;
    @Autowired private com.ironoath.web.hero.EquipLedgers equipLedgers;

    private HeroRoster rosterWith(String heroId, int level, int star, int awaken,
                                  int mainSkillLevel, int subSkillLevel) {
        HeroRoster roster = new HeroRoster();
        roster.restoreHero(new HeroInstance(heroId, level, 0L, star, awaken,
                mainSkillLevel, subSkillLevel));
        return roster;
    }

    @Test
    @DisplayName("属性按 B06 的口径进乘区：武力→攻击、统率→防御，slot 与入参顺序一致")
    void attributesMapToTheRightMultiplierZones() {
        HeroRoster roster = rosterWith(HERO, 1, 1, 0, 1, 1);
        List<HeroSnapshot> snapshots = mapper.snapshots(PLAYER, List.of(HERO), roster);

        assertThat(snapshots).hasSize(1);
        HeroSnapshot snapshot = snapshots.get(0);
        assertThat(snapshot.heroId()).isEqualTo(HERO);
        assertThat(snapshot.slot()).isZero();

        HeroAttrs attrs = heroStats.finalAttrs(equipLedgers.of(PLAYER), roster.hero(HERO));
        assertThat(snapshot.heroBonusFixed())
                .as("武力进攻击乘区（B05 的 HeroSnapshot.heroBonusFixed）")
                .isEqualTo(HeroCalculator.attrToBonus(attrs.might(), heroStats.rules()));
        assertThat(snapshot.defBonusFixed())
                .as("统率进防御乘区")
                .isEqualTo(HeroCalculator.attrToBonus(attrs.command(), heroStats.rules()));
        assertThat(snapshot.skills()).as("主将两个技能都要进战斗").hasSize(2);
    }

    @Test
    @DisplayName("技能数值随等级成长：1 级 = 表值，满级 = 表值 × (1 + STEP × 9)")
    void skillValueGrowsWithLevel() {
        HeroCfg cfg = configs.get(HeroCfg.class, HERO);
        SkillCfg mainSkill = configs.get(SkillCfg.class, cfg.mainSkill());

        SkillSnapshot level1 = firstSkill(mapper.snapshots(PLAYER, List.of(HERO),
                rosterWith(HERO, 1, 1, 0, 1, 1)));
        assertThat(level1.valueFixed())
                .as("1 级必须是表值本身，否则基础强度就与策划表对不上")
                .isEqualTo(mainSkill.value());

        int maxLevel = (int) configs.longParam("HERO_SKILL_MAX_LEVEL");
        SkillSnapshot maxed = firstSkill(mapper.snapshots(PLAYER, List.of(HERO),
                rosterWith(HERO, 1, 1, 0, maxLevel, 1)));
        long step = configs.fixedParam("SKILL_LEVEL_VALUE_STEP");
        long expected = FixedPoint.mul(mainSkill.value(),
                FixedPoint.add(FixedPoint.ONE, FixedPoint.mul(step, FixedPoint.of(maxLevel - 1L))));
        assertThat(maxed.valueFixed())
                .as("满级技能必须比 1 级强 —— 这条断言就是「升级不是骗钱的」的证据")
                .isEqualTo(expected);
        assertThat(maxed.valueFixed()).isGreaterThan(level1.valueFixed());
    }

    @Test
    @DisplayName("触发概率与持续回合不随等级变：缩放它们会改掉 B05 校准过的战斗节奏")
    void chanceAndDurationDoNotScaleWithLevel() {
        HeroCfg cfg = configs.get(HeroCfg.class, HERO);
        SkillCfg mainSkill = configs.get(SkillCfg.class, cfg.mainSkill());
        int maxLevel = (int) configs.longParam("HERO_SKILL_MAX_LEVEL");

        SkillSnapshot level1 = firstSkill(mapper.snapshots(PLAYER, List.of(HERO),
                rosterWith(HERO, 1, 1, 0, 1, 1)));
        SkillSnapshot maxed = firstSkill(mapper.snapshots(PLAYER, List.of(HERO),
                rosterWith(HERO, 1, 1, 0, maxLevel, 1)));

        assertThat(maxed.chanceFixed()).isEqualTo(level1.chanceFixed()).isEqualTo(mainSkill.chance());
        assertThat(maxed.durationRounds())
                .isEqualTo(level1.durationRounds())
                .isEqualTo((int) mainSkill.durationRounds());
        assertThat(maxed.skillId()).isEqualTo(mainSkill.id());
        assertThat(maxed.phase().name()).isEqualTo(mainSkill.trigger().name());
        assertThat(maxed.effect().name()).isEqualTo(mainSkill.effect().name());
    }

    @Test
    @DisplayName("副将位为空时跳过，但 slot 保留原始下标（站位决定损失分摊的前中后排）")
    void emptySlotsAreSkippedWithoutShiftingSlots() {
        HeroRoster roster = rosterWith(HERO, 1, 1, 0, 1, 1);
        // 中间留一个空位：slot 必须是 0 与 2，而不是 0 与 1 ——
        // slot 决定站位，站位决定前/中/后排的损失分摊，压缩下标等于把后排兵调到中排去挨打
        List<HeroSnapshot> snapshots = mapper.snapshots(PLAYER, Arrays.asList(HERO, null, HERO), roster);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0).slot()).isZero();
        assertThat(snapshots.get(1).slot()).isEqualTo(2);

        assertThat(mapper.snapshots(PLAYER, List.of(), roster)).isEmpty();
        assertThat(mapper.snapshots(PLAYER, null, roster)).isEmpty();
    }

    @Test
    @DisplayName("武将等级/星级/觉醒会抬高属性，因此也抬高战斗乘区（养成链路真的通到战斗）")
    void heroGrowthReachesTheBattle() {
        HeroSnapshot fresh = mapper.snapshots(PLAYER, List.of(HERO), rosterWith(HERO, 1, 1, 0, 1, 1)).get(0);
        HeroSnapshot grown = mapper.snapshots(PLAYER, List.of(HERO), rosterWith(HERO, 60, 4, 2, 1, 1)).get(0);
        assertThat(grown.heroBonusFixed())
                .as("练了 60 级 4 星 2 觉醒，攻击乘区必须比新抽到时高")
                .isGreaterThan(fresh.heroBonusFixed());
        assertThat(grown.defBonusFixed()).isGreaterThan(fresh.defBonusFixed());
    }

    private static SkillSnapshot firstSkill(List<HeroSnapshot> snapshots) {
        assertThat(snapshots).hasSize(1);
        List<SkillSnapshot> skills = snapshots.get(0).skills();
        assertThat(skills).as("主技能必须映射出来").isNotEmpty();
        return skills.get(0);
    }
}
