package com.ironoath.web;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.MatchRuleCfg;
import com.ironoath.core.power.Protection;
import com.ironoath.core.power.TargetSearch;
import com.ironoath.core.power.Tyranny;
import com.ironoath.web.dto.generated.DistanceBand;
import com.ironoath.web.dto.generated.ResourceHint;
import com.ironoath.web.dto.generated.TyrannyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：B08 的契约一致性 —— 协议枚举 ↔ game-core 枚举，以及「保护规则恰好三条」这条生态红线。
 * 依赖：JUnit 5 + AssertJ；只有 match_rule 那条断言需要容器（读配置表）。
 *
 * <p><b>为什么枚举一致性要单独钉住</b>：协议侧来自 contract/proto 的 JSON Schema，
 * 内部侧来自 game-core 的手写枚举，生成器无法自动比对。一旦漂移，
 * 症状是服务端下发的字符串在客户端解析成 undefined（TS 侧不报错、UI 直接空白），
 * 或者服务端 {@code valueOf} 抛 IllegalArgumentException 变成 500。
 * 两种都不是「配置写错」那样一眼能看出的问题。
 *
 * <p><b>「恰好三条保护」是本测试里最重要的一条断言</b>。C00 公理二说：
 * 「数一数保护规则的数量，超过三条就要问自己这条规则会阻止哪个社交行为」。
 * 把这条公理做成表结构 + 断言之后，任何人想加第四条保护都必须同时改
 * match_rule.json、game-core 的 Protection、以及这里 —— 三处一起改才动得了。
 * 写在文档里靠自觉，和写成会失败的测试，是两种完全不同的约束力。
 */
@SpringBootTest
@ActiveProfiles("test")
class PowerContractParityTest {

    @Autowired private ConfigRegistry configs;

    @Test
    @DisplayName("协议 TyrannyLevel 与 game-core 的 Tyranny.Level 完全一致（含顺序）")
    void tyrannyLevelMatchesCore() {
        List<String> contract = Arrays.stream(TyrannyLevel.values()).map(Enum::name).toList();
        List<String> core = Arrays.stream(Tyranny.Level.values()).map(Enum::name).toList();
        assertThat(contract)
                .as("暴虐档位是下发给客户端的字符串；漂移会让「公敌」标签在 UI 上变成空白，"
                        + "而玩家看不到标签就不会去围剿，暴虐值机制的闭环当场断掉")
                .containsExactlyElementsOf(core);
        assertThat(contract).containsExactly("COMMONER", "TYRANT", "BRUTE", "PUBLIC_ENEMY");
    }

    @Test
    @DisplayName("协议 DistanceBand / ResourceHint 与 game-core 的分档枚举一致")
    void searchBandsMatchCore() {
        assertThat(Arrays.stream(DistanceBand.values()).map(Enum::name).toList())
                .as("距离只给三档：多一档就等于开始下发精确距离（B08 验收 12 的反面）")
                .containsExactlyElementsOf(
                        Arrays.stream(TargetSearch.DistanceBand.values()).map(Enum::name).toList());
        assertThat(Arrays.stream(ResourceHint.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(
                        Arrays.stream(TargetSearch.ResourceHint.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("C00 公理二：保护规则恰好三条，且三条都禁止主动攻击")
    void thereAreExactlyThreeProtectionsAndAllBlockAttack() {
        List<MatchRuleCfg> protections = configs.all(MatchRuleCfg.class).stream()
                .filter(row -> row.kind() == MatchRuleCfg.Kind.PROTECTION)
                .toList();
        assertThat(protections)
                .as("保护规则超过三条就会开始阻止社交行为（C00 公理二）。"
                        + "想加第四条，请先回答：这条规则会阻止哪个社交行为？")
                .hasSize(3);
        assertThat(protections)
                .as("三条保护都必须付对价：护盾期间不能主动攻击，"
                        + "否则护盾就是免费的进攻准备期，保护机制会被反向利用")
                .allMatch(MatchRuleCfg::blocksActiveAttack);
        assertThat(protections.stream().map(MatchRuleCfg::id).toList())
                .containsExactlyInAnyOrder("mr_protection_newcomer",
                        "mr_protection_victim_shield", "mr_protection_shield_no_attack");
    }

    @Test
    @DisplayName("保护规则的参数外键都能在全局表里找到，且两档护盾严格递进")
    void protectionParamsResolveAndEscalate() {
        MatchRuleCfg victim = configs.all(MatchRuleCfg.class).stream()
                .filter(row -> row.id().equals("mr_protection_victim_shield"))
                .findFirst().orElseThrow();
        // REF 校验器已经保证了这些 id 存在；这里再断言一次「两档都填了」——
        // 少填一档不会让校验器报错（字段是可选的），但会让第二档护盾永远不触发
        assertThat(victim.windowParam()).as("统计窗口不能缺").isNotBlank();
        assertThat(victim.triggerCountParam()).as("第一档触发人数不能缺").isNotBlank();
        assertThat(victim.durationParam()).as("第一档时长不能缺").isNotBlank();
        assertThat(victim.triggerCountParam2()).as("第二档触发人数不能缺").isNotBlank();
        assertThat(victim.durationParam2()).as("第二档时长不能缺").isNotBlank();

        Protection.Rules rules = new Protection.Rules(
                (int) configs.longParam("NEWCOMER_PROTECT_CITY_LEVEL"),
                configs.longParam(victim.windowParam()) * 1000L,
                (int) configs.longParam(victim.triggerCountParam()),
                configs.longParam(victim.durationParam()) * 3600_000L,
                (int) configs.longParam(victim.triggerCountParam2()),
                configs.longParam(victim.durationParam2()) * 3600_000L);
        // Protection.Rules 的构造器已经强制了两档严格递增，能构造出来就说明配置合法。
        // 这里把值也钉一下：有人把 4h 改成 24h 时，这条断言会比「玩家一天只能被打三次」的投诉先到
        assertThat(rules.triggerCountTier1()).isEqualTo(3);
        assertThat(rules.triggerCountTier2()).isEqualTo(5);
        assertThat(rules.shieldMillisTier1()).isEqualTo(4L * 3600_000L);
        assertThat(rules.shieldMillisTier2()).isEqualTo(12L * 3600_000L);
        assertThat(rules.windowMillis()).isEqualTo(24L * 3600_000L);
    }

    @Test
    @DisplayName("skill 表的 trigger/effect 与内核的 SkillPhase/SkillEffect 一致（靠 valueOf 翻译，漂移只会在战斗中炸）")
    void skillEnumsMatchTheBattleKernel() {
        // 这两组枚举之间没有生成器校验：skill 表侧是 fieldTypes 的 ENUM，内核侧是手写枚举，
        // 桥接靠 HeroBattleMapper 里的 valueOf。漂移不会在启动期暴露，
        // 而是等到某个武将第一次放技能时抛 IllegalArgumentException —— 战斗打到一半炸掉
        assertThat(Arrays.stream(com.ironoath.config.cfg.SkillCfg.Trigger.values())
                .map(Enum::name).toList())
                .as("skill.trigger 必须能被翻译成 SkillPhase")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(com.ironoath.battle.SkillPhase.values())
                                .map(Enum::name).toList());
        assertThat(Arrays.stream(com.ironoath.config.cfg.SkillCfg.Effect.values())
                .map(Enum::name).toList())
                .as("skill.effect 必须能被翻译成 SkillEffect")
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(com.ironoath.battle.SkillEffect.values())
                                .map(Enum::name).toList());
    }

    @Test
    @DisplayName("验收9 配置侧：复仇 +15% / 哀兵 +10% / 围剿 +15%，且哀兵刻意低于复仇")
    void counterAttackBonusesMatchTheSpec() {
        // 内核侧（game-battle 的 BattleModifierTest）钉的是这三个数对应的定点字面量，
        // 这里钉的是配置表本身。两处各钉一半：改了配置而忘了内核口径，会有一边变红。
        // 三项都必须为正 —— 0 或负值意味着弱者的反击路径实际上不存在，
        // 而组团反击正是本项目给弱者的唯一出路（C00 公理一）
        long revenge = configs.fixedParam("BONUS_REVENGE");
        long mourning = configs.fixedParam("BONUS_MOURNING");
        long crusade = configs.fixedParam("BONUS_SIEGE_PUBLIC_ENEMY");
        assertThat(revenge).as("B08 §6：复仇 +15%").isEqualTo(FixedPoint.parse("0.15"));
        assertThat(mourning).as("B08 §6：哀兵 +10%").isEqualTo(FixedPoint.parse("0.10"));
        assertThat(crusade).as("B08 §6：围剿公敌 +15%，与复仇同档").isEqualTo(FixedPoint.parse("0.15"));
        assertThat(mourning)
                .as("哀兵必须低于复仇：防守是被动收益，主动反击应当更有回报，"
                        + "否则玩家没有从「被保护」转向「叫人反打」的动力")
                .isLessThan(revenge);
        assertThat(configs.longParam("REVENGE_WINDOW_SECONDS"))
                .as("复仇窗口 24h：覆盖一个完整作息周期，下线再上线仍能复仇")
                .isEqualTo(86400L);
    }

    @Test
    @DisplayName("搜索权重之和为 1.0，且集结上限与单人上限共用同一个参数（不设第二个家）")
    void searchWeightsAndRallyBandShareOneHome() {
        TargetSearch.Rules rules = new TargetSearch.Rules(
                (int) configs.longParam("SEARCH_MAX_RADIUS"),
                configs.longParam("SEARCH_ACTIVE_WINDOW_HOURS") * 3600_000L,
                (int) configs.longParam("SEARCH_DEFAULT_COUNT"),
                (int) configs.longParam("SEARCH_MAX_COUNT"),
                configs.fixedParam("SEARCH_WEIGHT_POWER"),
                configs.fixedParam("SEARCH_WEIGHT_DISTANCE"),
                configs.fixedParam("SEARCH_WEIGHT_RESOURCE"),
                configs.fixedParam("SEARCH_WEIGHT_RANDOM"),
                configs.fixedParam("SEARCH_NEAR_RATIO"),
                configs.fixedParam("SEARCH_MID_RATIO"),
                configs.fixedParam("SEARCH_RICH_RATIO"),
                configs.fixedParam("SEARCH_POOR_RATIO"),
                configs.fixedParam("SEARCH_PEER_RATIO_MIN"),
                configs.fixedParam("SEARCH_PEER_RATIO_MAX"));
        assertThat(rules.weightPower() + rules.weightDistance()
                + rules.weightResource() + rules.weightRandom())
                .isEqualTo(com.ironoath.common.num.FixedPoint.ONE);

        // 集结行必须引用 PVP_POWER_MAX_RATIO 本身，不能有一个独立的 RALLY_POWER_FACTOR。
        // 两个家意味着改一处忘一处，集结与单人的口径就会悄悄分叉，而没有任何测试会变红
        MatchRuleCfg rally = configs.all(MatchRuleCfg.class).stream()
                .filter(row -> row.id().equals("mr_scenario_rally"))
                .findFirst().orElseThrow();
        MatchRuleCfg normal = configs.all(MatchRuleCfg.class).stream()
                .filter(row -> row.id().equals("mr_scenario_normal_attack"))
                .findFirst().orElseThrow();
        assertThat(rally.powerMaxParam())
                .as("集结的上限倍率必须与单人攻击同源，差别只在 √N")
                .isEqualTo(normal.powerMaxParam());
        assertThat(rally.powerMinParam()).isEqualTo(normal.powerMinParam());
    }
}
