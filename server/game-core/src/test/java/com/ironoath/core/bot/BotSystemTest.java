package com.ironoath.core.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

/**
 * 职责：B11 Bot 核心的单测 —— 行为树（§三）、拟人化（§四、验收 9/11）、
 * 数量与强度调节（§五、验收 3/4）、合规红线（§六/§七、验收 5）。
 * 依赖：JUnit 5 + AssertJ + game-core 的 bot 包（纯 Java，零框架）。
 *
 * <p>夹具里的数值抄自 contract/config（反应延迟 3~30 秒、帮助延迟 10~120 秒、
 * 失误率 5%~20%、成长系数 0.7~1.3、密度 20/5/1.5、频控 3 次/24h）。
 * 「表值 == 文档值」那一层由 game-config 的 BotConfigTest 断言，
 * 所以本类可以放心用手抄夹具 —— 手抄的那份若与表漂移，BotConfigTest 会先变红。
 *
 * <p><b>本类最重要的一条断言是「决策树里没有任何执行入口」</b>：
 * BotDecisionTree 只依赖 Rng 与 FixedPoint，拿不到仓储、拿不到钱包、拿不到任何 service。
 * 这是 B11 头号铁律（Bot 必须走与真人完全相同的 service 层）在类型层面的保证 ——
 * 想开特权捷径也得先加一个依赖，而加依赖会改动构造器签名，评审时一眼就能看见。
 */
class BotSystemTest {

    private static final long SECOND = 1000L;
    private static final long HOUR = 3600 * SECOND;
    private static final long DAY = 24 * HOUR;

    // ---------- 夹具 ----------

    private static BotProfile.Persona persona(long mistakeFixed, long delayMin, long delayMax) {
        return new BotProfile.Persona(42L, 7L, 99L, List.of(12, 13, 20, 21, 22),
                delayMin, delayMax, mistakeFixed);
    }

    private static BotProfile profile(long aggression, long greed, long sociability, long activeness,
                                      long growth, BotProfile.Persona persona) {
        return new BotProfile("bot-1", "bot_linju",
                new BotProfile.AiProfile(aggression, greed, sociability, activeness),
                persona, growth);
    }

    /** 从不失误的邻居 Bot。 */
    private static BotProfile steadyBot() {
        return profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
    }

    private static BotDecisionTree.WorldState state(boolean underAttack, boolean freeQueue, boolean affordBuild,
                                                    boolean popFull, boolean affordTrain,
                                                    boolean stamina, boolean marchSlot,
                                                    boolean inAlliance, long idleMillis) {
        return new BotDecisionTree.WorldState(underAttack, freeQueue, affordBuild, popFull, affordTrain,
                stamina, marchSlot, inAlliance, false, false, idleMillis);
    }

    /** 什么都不能做的空状态。 */
    private static BotDecisionTree.WorldState idleState() {
        return state(false, false, false, true, false, false, false, false, 0L);
    }

    // ---------- 参数校验 ----------

    @Test
    @DisplayName("验收9 在构造期就被钉住：反应延迟下界 < 3 秒或上界 > 30 秒都直接拒绝")
    void personaRejectsDelaysOutsideAcceptance9() {
        assertThatThrownBy(() -> persona(0L, 0L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 3 秒");
        assertThatThrownBy(() -> persona(0L, 2L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 3 秒");
        assertThatThrownBy(() -> persona(0L, 3L, 60L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("<= 30 秒");
        assertThatThrownBy(() -> persona(0L, 20L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("区间非法");
        // 合法区间通过
        assertThat(persona(0L, 3L, 30L).reactionDelayMinSec()).isEqualTo(3L);
    }

    @Test
    @DisplayName("活跃时段为空或越界都拒绝：没有活跃时段的 Bot 永远不会 tick，等于地图上一具尸体")
    void personaRejectsEmptyOrInvalidActiveHours() {
        assertThatThrownBy(() -> new BotProfile.Persona(1L, 1L, 1L, List.of(), 3L, 30L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("activeHours");
        assertThatThrownBy(() -> new BotProfile.Persona(1L, 1L, 1L, List.of(24), 3L, 30L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[0,23]");
        assertThatThrownBy(() -> new BotProfile.Persona(1L, 1L, 1L, List.of(-1), 3L, 30L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[0,23]");
    }

    @Test
    @DisplayName("四个 AI 维度与失误率都必须落在定点 [0,1.0]")
    void aiProfileRejectsOutOfRangeRatios() {
        assertThatThrownBy(() -> new BotProfile.AiProfile(10001L, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("aggression");
        assertThatThrownBy(() -> new BotProfile.AiProfile(0, 0, 0, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("activeness");
        assertThatThrownBy(() -> persona(10001L, 3L, 30L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mistakeRate");
    }

    // ---------- 行为树（§三） ----------

    @Test
    @DisplayName("受击反应优先于一切自发行为：正在被抢的 Bot 不该先把手里的资源花掉")
    void attackReactionPreemptsEverything() {
        BotDecisionTree tree = new BotDecisionTree();
        BotDecisionTree.WorldState attacked = state(true, true, true, false, true, true, true, true, 0L);
        BotDecisionTree.Decision decision = tree.decide(steadyBot(), attacked, Rng.of(7), 1000L);
        assertThat(decision.action()).isEqualTo(BotDecisionTree.Action.REACT_ATTACK);
        assertThat(decision.delayMillis())
                .as("验收9：反应延迟必须落在 [3s, 30s]，禁止 0ms")
                .isBetween(3 * SECOND, 30 * SECOND);
    }

    @Test
    @DisplayName("行为树优先级：建造队列空闲优先于训练，训练优先于打野")
    void decisionFollowsDocumentedPriority() {
        BotDecisionTree tree = new BotDecisionTree();
        Rng rng = Rng.of(11);

        assertThat(tree.decide(steadyBot(), state(false, true, true, false, true, true, true, false, 0L),
                rng, 0L).action())
                .as("有空闲队列且资源够 ⇒ 先升级建筑（§三 第 1 条）")
                .isEqualTo(BotDecisionTree.Action.UPGRADE_BUILDING);

        assertThat(tree.decide(steadyBot(), state(false, false, false, false, true, true, true, false, 0L),
                rng, 0L).action())
                .as("队列满了但人口未满 ⇒ 训练（§三 第 2 条）")
                .isEqualTo(BotDecisionTree.Action.TRAIN_TROOPS);

        // 第 3 条这一格现在有三种落点：打野、采集、掠袭（掠袭是 §一/§六 补进同一格的分支，
        // 见收口清单 #94）。这条用例验的是**优先级**（第 3 条排在 1、2 之后），
        // 具体落在哪一种由 greed/aggression 与随机源决定
        assertThat(tree.decide(steadyBot(), state(false, false, false, true, false, true, true, false, 0L),
                rng, 0L).action())
                .as("建不了也训不了 ⇒ 打野 / 采集 / 掠袭（§三 第 3 条）")
                .isIn(BotDecisionTree.Action.HUNT_MONSTER, BotDecisionTree.Action.GATHER_RESOURCE,
                        BotDecisionTree.Action.RAID);

        assertThat(tree.decide(steadyBot(), idleState(), rng, 0L).action())
                .isEqualTo(BotDecisionTree.Action.IDLE);
    }

    @Test
    @DisplayName("greed 决定打野还是采集：贪婪度 0 从不采集，1 必采集（掠袭分支已按 aggression=0 关掉）")
    void greedSplitsHuntAndGather() {
        BotDecisionTree tree = new BotDecisionTree();
        BotDecisionTree.WorldState ready = state(false, false, false, true, false, true, true, false, 0L);

        // aggression=0 ⇒ 掠袭掷骰永远不过（那条分支 2026-09-12 加进来的，见收口清单 #94），
        // 于是这里量到的就是纯粹的 greed 分流。断言改成扫一批种子而不是押一个：
        // 加一条分支会改变随机数消费顺序，押单一种子的用例会因为"换了种子"而假红
        BotProfile farmer = profile(0L, 0L,
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        BotProfile greedy = profile(0L, FixedPoint.SCALE,
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        for (int seed = 0; seed < 50; seed++) {
            assertThat(tree.decide(farmer, ready, Rng.of(seed), 0L).action())
                    .as("greed=0 ⇒ 不倾向采集（seed=%s）", seed).isEqualTo(BotDecisionTree.Action.HUNT_MONSTER);
            assertThat(tree.decide(greedy, ready, Rng.of(seed), 0L).action())
                    .as("greed=1.0 ⇒ 必倾向采集（seed=%s）", seed).isEqualTo(BotDecisionTree.Action.GATHER_RESOURCE);
        }
    }

    @Test
    @DisplayName("C3（#94）：掠袭倾向由 aggression 决定 —— 1.0 必去抢，0 从不去抢")
    void aggressionSplitsRaidFromPve() {
        BotDecisionTree tree = new BotDecisionTree();
        BotDecisionTree.WorldState ready = state(false, false, false, true, false, true, true, false, 0L);

        BotProfile raider = profile(FixedPoint.SCALE, FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        BotProfile peaceful = profile(0L, FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));

        for (int seed = 0; seed < 30; seed++) {
            assertThat(tree.decide(raider, ready, Rng.of(seed), 0L).action())
                    .as("aggression=1.0 ⇒ 体力与队列空闲时必去掠袭（seed=%s）", seed)
                    .isEqualTo(BotDecisionTree.Action.RAID);
            assertThat(tree.decide(peaceful, ready, Rng.of(seed), 0L).action())
                    .as("aggression=0 ⇒ 从不掠袭，回到打野/采集（seed=%s）", seed)
                    .isIn(BotDecisionTree.Action.HUNT_MONSTER, BotDecisionTree.Action.GATHER_RESOURCE);
        }
    }

    @Test
    @DisplayName("C3（#94）：不在联盟里且社交倾向命中时会去申请入盟 —— 没有它联盟那一支永远是死的")
    void lonelyBotSeeksAnAlliance() {
        BotDecisionTree tree = new BotDecisionTree();
        // 把体力与队列都占掉，免得前面几支先返回；inAlliance=false
        BotDecisionTree.WorldState lonely = state(false, false, false, false, false, false, false, false, 0L);

        BotProfile social = profile(0L, 0L, FixedPoint.SCALE, FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        for (int seed = 0; seed < 30; seed++) {
            assertThat(tree.decide(social, lonely, Rng.of(seed), 0L).action())
                    .as("sociability=1.0 ⇒ 必申请入盟（seed=%s）", seed)
                    .isEqualTo(BotDecisionTree.Action.SEEK_ALLIANCE);
        }

        BotProfile unsocial = profile(0L, 0L, 0L, FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        assertThat(tree.decide(unsocial, lonely, Rng.of(5), 0L).action())
                .as("sociability=0 ⇒ 不申请，落到 IDLE（同样不该乱迁城：idleMillis=0）")
                .isEqualTo(BotDecisionTree.Action.IDLE);
    }

    @Test
    @DisplayName("失误率：0% 永远选最优，100% 永远选次优（次优是合法动作，不是不可能的动作）")
    void mistakeRateSwitchesBetweenOptimalAndSuboptimal() {
        BotDecisionTree tree = new BotDecisionTree();
        BotDecisionTree.WorldState ready = state(false, true, true, false, true, true, true, false, 0L);

        BotProfile never = profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        BotDecisionTree.Decision optimal = tree.decide(never, ready, Rng.of(5), 0L);
        assertThat(optimal.action()).isEqualTo(BotDecisionTree.Action.UPGRADE_BUILDING);
        assertThat(optimal.suboptimal()).isFalse();

        BotProfile always = profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(FixedPoint.SCALE, 8L, 20L));
        BotDecisionTree.Decision mistake = tree.decide(always, ready, Rng.of(5), 0L);
        assertThat(mistake.suboptimal()).isTrue();
        assertThat(mistake.action())
                .as("失误是「选下一条可执行的动作」而不是乱选：乱选会产生资源不够却去训练这种不可能的行为")
                .isEqualTo(BotDecisionTree.Action.TRAIN_TROOPS);
        assertThat(mistake.reason()).contains("失误");
    }

    @Test
    @DisplayName("失误率 10% 时，1000 次决策里失误落在合理区间（不是 0 也不是全部）")
    void mistakeRateIsStatisticallyRespected() {
        BotDecisionTree tree = new BotDecisionTree();
        BotProfile bot = profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(FixedPoint.parse("0.10"), 8L, 20L));
        BotDecisionTree.WorldState ready = state(false, true, true, false, true, true, true, false, 0L);
        int mistakes = 0;
        for (int i = 0; i < 1000; i++) {
            if (tree.decide(bot, ready, Rng.of(i), 0L).suboptimal()) {
                mistakes++;
            }
        }
        assertThat(mistakes).as("1000 次里 10% 失误，允许 ±5 个百分点的抽样波动")
                .isBetween(50, 150);
    }

    @Test
    @DisplayName("联盟行为的延迟：只有帮助带 10~120 秒迟疑，捐献与集结是自发行为不需要延迟")
    void allianceHelpCarriesHumanDelay() {
        BotDecisionTree tree = new BotDecisionTree();
        BotProfile social = profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.SCALE, FixedPoint.parse("0.60"),
                FixedPoint.parse("1.00"), persona(0L, 8L, 20L));
        BotDecisionTree.WorldState inAlliance = state(false, false, false, true, false,
                false, false, true, 0L);

        int helpCount = 0;
        for (int i = 0; i < 300; i++) {
            BotDecisionTree.Decision decision = tree.decide(social, inAlliance, Rng.of(i), 0L);
            if (decision.action() == BotDecisionTree.Action.ALLIANCE_HELP) {
                helpCount++;
                assertThat(decision.delayMillis())
                        .as("§四：联盟求助后 10~120 秒才帮忙，禁止 0ms")
                        .isBetween(10 * SECOND, 120 * SECOND);
            } else if (decision.action() == BotDecisionTree.Action.ALLIANCE_DONATE
                    || decision.action() == BotDecisionTree.Action.JOIN_RALLY) {
                assertThat(decision.delayMillis())
                        .as("捐献与集结是自发的，不是对求助的响应").isZero();
            }
        }
        assertThat(helpCount).as("300 次里应当抽到过帮助").isPositive();
    }

    @Test
    @DisplayName("长时间无互动才可能迁城，且概率只有 15%：邻居频繁搬家就没有「常驻周边」的生活感了")
    void relocationRequiresLongIdle() {
        BotDecisionTree tree = new BotDecisionTree();
        BotDecisionTree.WorldState shortIdle = state(false, false, false, true, false,
                false, false, false, HOUR);
        assertThat(tree.decide(steadyBot(), shortIdle, Rng.of(1), 0L).action())
                .as("1 小时无互动还不算久").isEqualTo(BotDecisionTree.Action.IDLE);

        int relocated = 0;
        BotDecisionTree.WorldState longIdle = state(false, false, false, true, false,
                false, false, false, 12 * HOUR);
        for (int i = 0; i < 200; i++) {
            if (tree.decide(steadyBot(), longIdle, Rng.of(i), 0L).action()
                    == BotDecisionTree.Action.RELOCATE_CITY) {
                relocated++;
            }
        }
        assertThat(relocated).as("200 次里 15% 概率，允许抽样波动").isBetween(10, 50);
    }

    @Test
    @DisplayName("追赶补偿只提高 tick 频率倍率，不改任何数值（§十 禁止项：不要直接改数值）")
    void catchUpOnlyRaisesTickFrequency() {
        BotDecisionTree tree = new BotDecisionTree();
        assertThat(tree.catchUpTickMultiplier(steadyBot(), false))
                .as("不落后时倍率为 1.0").isEqualTo(FixedPoint.SCALE);
        long boosted = tree.catchUpTickMultiplier(steadyBot(), true);
        assertThat(boosted).as("落后时倍率 = 1.5 × 成长系数 1.0（定点）")
                .isEqualTo(FixedPoint.parse("1.5"));
        // 成长慢的 Bot 倍率更高：它需要更勤快才追得上
        BotProfile slow = profile(FixedPoint.parse("0.30"), FixedPoint.parse("0.50"),
                FixedPoint.parse("0.45"), FixedPoint.parse("0.60"),
                FixedPoint.parse("1.20"), persona(0L, 8L, 20L));
        assertThat(tree.catchUpTickMultiplier(slow, true))
                .isEqualTo(FixedPoint.parse("1.8"));
        // 倍率有界：不能让追赶期的 Bot 在几次 tick 内跨过整个圈层
        assertThat(boosted).isLessThan(FixedPoint.parse("2.0"));
    }

    @Test
    @DisplayName("决策必须带理由：没有理由的决策无法排查「这个 Bot 为什么做了这件事」")
    void decisionAlwaysCarriesReason() {
        BotDecisionTree tree = new BotDecisionTree();
        assertThatThrownBy(() -> new BotDecisionTree.Decision(BotDecisionTree.Action.IDLE, false, 0L, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reason");
        assertThat(tree.decide(steadyBot(), idleState(), Rng.of(1), 0L).reason()).isNotBlank();
    }

    // ---------- 名字（验收 11） ----------

    @Test
    @DisplayName("验收11：连抽 5000 个名字无重复、无机器感命名")
    void namesAreUniqueAndHuman() {
        // 池子取与 bot_name 表同量级的规模：太小会撞上耗尽回退，
        // 那条路径由 nameGeneratorFailsLoudlyWhenPoolExhausted 单独测
        List<String> surnames = List.of("裴", "沈", "李", "王", "张", "刘", "陈", "杨", "赵", "黄",
                "周", "吴", "徐", "孙", "马", "朱", "胡", "郭", "何", "高",
                "林", "罗", "郑", "梁", "谢", "宋", "唐", "许", "韩", "冯");
        List<String> givens = List.of("惊澜", "砚秋", "承鄞", "长风", "昭明", "怀瑾", "子昂", "青梧",
                "玄舟", "令仪", "守拙", "望舒", "拂衣", "归鸿", "听雪",
                "照野", "栖迟", "执成", "破阵", "临渊", "观星", "抱朴", "问渠", "踏歌",
                "折梅", "负霜", "燃犀", "枕戈", "横江", "溯洄",
                "藏锋", "拾遗", "叩关", "斩棘", "耕烟", "牧云", "渡川", "守城", "巡边", "屯田");
        List<String> titles = List.of("铁誓", "孤城", "北望", "残阳");
        BotNameGenerator generator = new BotNameGenerator(surnames, givens, titles,
                FixedPoint.parse("0.25"));

        Set<String> taken = new HashSet<>();
        List<String> generated = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String name = generator.generate(Rng.of(i), taken);
            assertThat(taken.add(name)).as("第 %d 个名字重复：%s", i, name).isTrue();
            generated.add(name);
        }
        assertThat(generated).hasSize(500);
        for (String name : generated) {
            assertThat(name).as("禁止「玩家12345」式机器名：%s", name)
                    .doesNotMatch(".*\\d+.*")
                    .doesNotContain("玩家", "测试", "bot");
            assertThat(name.length()).as("名字长度应当像真人昵称：%s", name).isBetween(2, 6);
        }
        // 称号只出现在约四分之一的名字上
        long withTitle = generated.stream().filter(name -> name.length() >= 4).count();
        assertThat(withTitle).as("称号概率 0.25，500 个里应当有几十个").isBetween(30L, 250L);
    }

    @Test
    @DisplayName("名字池耗尽时抛错而不是返回重复名字：重名的表现是「两个张三互相收不到私信」")
    void nameGeneratorFailsLoudlyWhenPoolExhausted() {
        BotNameGenerator tiny = new BotNameGenerator(List.of("裴"), List.of("惊澜"), List.of(), 0L);
        assertThat(tiny.combinationCount()).isEqualTo(1L);
        Set<String> taken = new HashSet<>(List.of("裴惊澜"));
        assertThatThrownBy(() -> tiny.generate(Rng.of(1), taken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("名字池已耗尽");
    }

    @Test
    @DisplayName("称号概率 > 0 却给了空称号池 ⇒ 构造期就拒绝（那是白配了一个概率）")
    void titleChanceRequiresNonEmptyPool() {
        assertThatThrownBy(() -> new BotNameGenerator(List.of("裴"), List.of("惊澜"), List.of(),
                FixedPoint.parse("0.25")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("称号池为空");
        // 概率为 0 时允许空池
        assertThat(new BotNameGenerator(List.of("裴"), List.of("惊澜"), List.of(), 0L)
                .combinationCount()).isEqualTo(1L);
    }

    // ---------- 作息（验收 10） ----------

    @Test
    @DisplayName("验收10：按 bot_schedule 的真实曲线，凌晨 2-5 点 / 晚 20-22 点 = 3.6% < 20%")
    void scheduleNightToPeakRatioMatchesAcceptance10() {
        BotSchedule schedule = new BotSchedule(new BotSchedule.Rules(3, 8, realWeights()));
        long ratio = schedule.nightToPeakRatio();
        assertThat(ratio)
                .as("定点比值：凌晨 2-5 点权重合计 10，晚 20-22 点合计 280 ⇒ 357（3.57%）")
                .isLessThan(FixedPoint.parse("0.20"));
        assertThat(ratio).isPositive();
    }

    @Test
    @DisplayName("抽 20000 次 tick 小时，凌晨 2-5 点的占比确实远低于晚 20-22 点")
    void pickHourFollowsTheWeightCurve() {
        BotSchedule schedule = new BotSchedule(new BotSchedule.Rules(3, 8, realWeights()));
        int night = 0;
        int peak = 0;
        for (int i = 0; i < 20000; i++) {
            int hour = schedule.pickHour(Rng.of(i));
            if (hour >= 2 && hour <= 5) {
                night++;
            } else if (hour >= 20 && hour <= 22) {
                peak++;
            }
        }
        assertThat(peak).as("晚高峰应当被抽到很多次").isGreaterThan(3000);
        assertThat(night * 5L).as("凌晨占比必须远低于晚高峰的 20%").isLessThan(peak);
    }

    @Test
    @DisplayName("候选行为按权重降序：决策树从前往后找第一个可执行的，于是「最像真人会做的事」优先")
    void candidateActionsAreOrderedByWeight() {
        BotSchedule schedule = new BotSchedule(new BotSchedule.Rules(3, 8, realWeights()));
        List<BotSchedule.Action> at20 = schedule.candidateActions(20);
        assertThat(at20).isNotEmpty();
        assertThat(at20.get(0)).as("晚 8 点最该做的是上线").isEqualTo(BotSchedule.Action.LOGIN);
        // 凌晨 3 点只有 LOGIN/LOGOUT 这类全天行为，集结与捐献不在候选里
        assertThat(schedule.candidateActions(3))
                .doesNotContain(BotSchedule.Action.JOIN_RALLY, BotSchedule.Action.DONATE);
    }

    @Test
    @DisplayName("每天 tick 次数按活跃度在 [3,8] 内插值")
    void ticksPerDayInterpolatesByActiveness() {
        BotSchedule schedule = new BotSchedule(new BotSchedule.Rules(3, 8, realWeights()));
        assertThat(schedule.ticksPerDay(0L)).isEqualTo(3);
        assertThat(schedule.ticksPerDay(FixedPoint.SCALE)).isEqualTo(8);
        assertThat(schedule.ticksPerDay(FixedPoint.parse("0.5"))).isBetween(3, 8);
    }

    @Test
    @DisplayName("权重全为 0 的行为在构造期就被拒绝：那等于这个行为永远不会发生，应当删行而不是留死数据")
    void scheduleRejectsAllZeroWeights() {
        Map<BotSchedule.Action, Map<Integer, Integer>> weights = new EnumMap<>(BotSchedule.Action.class);
        weights.put(BotSchedule.Action.LOGIN, Map.of(0, 0, 1, 0));
        assertThatThrownBy(() -> new BotSchedule.Rules(3, 8, weights))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("权重全为 0");
    }

    /** bot_schedule 表里 LOGIN 行的真实权重（凌晨 2-5 点合计 10，晚 20-22 点合计 280）。 */
    private static Map<BotSchedule.Action, Map<Integer, Integer>> realWeights() {
        int[] login = {12, 8, 3, 2, 2, 3, 8, 15, 25, 30, 28, 30,
            60, 55, 30, 28, 30, 35, 50, 65, 100, 95, 85, 40};
        Map<Integer, Integer> byHour = new java.util.TreeMap<>();
        for (int hour = 0; hour < 24; hour++) {
            byHour.put(hour, login[hour]);
        }
        Map<Integer, Integer> rallyHours = new java.util.TreeMap<>();
        for (int hour : new int[]{12, 13, 19, 20, 21, 22}) {
            rallyHours.put(hour, (int) Math.round(login[hour] * 0.45));
        }
        Map<BotSchedule.Action, Map<Integer, Integer>> weights = new EnumMap<>(BotSchedule.Action.class);
        weights.put(BotSchedule.Action.LOGIN, byHour);
        weights.put(BotSchedule.Action.JOIN_RALLY, rallyHours);
        return weights;
    }

    // ---------- 战力校准（验收 3） ----------

    private static BotTuning tuning() {
        return new BotTuning(new BotTuning.Rules(
                FixedPoint.parse("0.80"), FixedPoint.parse("0.95"),
                FixedPoint.parse("0.70"), FixedPoint.parse("1.00"),
                3, 5000,
                FixedPoint.parse("20.0"), FixedPoint.parse("5.0"), FixedPoint.parse("1.5")));
    }

    @Test
    @DisplayName("验收3：目标战力永远略低于真人均值，且比值落在 [0.7, 1.0] 的验收带内")
    void targetPowerStaysBelowHumanAverage() {
        BotTuning tuning = tuning();
        long humanAverage = 100_000L;
        // 原型系数扫一遍 0.5~2.0：两头都会被夹回验收带 [0.7, 1.0]
        for (String archetype : List.of("0.50", "0.80", "1.00", "1.20", "2.00")) {
            long target = tuning.targetPower(humanAverage, FixedPoint.parse(archetype));
            double ratio = (double) target / humanAverage;
            assertThat(ratio)
                    .as("原型系数 %s 时的战力比必须落在验收带 [0.7, 1.0]，实际 %.3f",
                            archetype, ratio)
                    .isBetween(0.70, 1.00);
            assertThat(tuning.passesPowerCheck(target, humanAverage)).isTrue();
        }
        // 中点系数 (0.80+0.95)/2 = 0.875，原型系数 1.0 时目标就是均值的 87.5%
        assertThat(tuning.targetPower(humanAverage, FixedPoint.SCALE)).isEqualTo(87_500L);
        // 原型系数把军阀抬高、把陪跑者压低，但都不穿出验收带
        assertThat(tuning.targetPower(humanAverage, FixedPoint.parse("1.5")))
                .as("上端被夹到均值的 1.0 倍").isEqualTo(100_000L);
        assertThat(tuning.targetPower(humanAverage, FixedPoint.parse("0.30")))
                .as("下端被夹到均值的 0.7 倍").isEqualTo(70_000L);
    }

    @Test
    @DisplayName("真人均值为 0 时目标战力为 0（开服初期还没有可比较的对象），不抛错")
    void targetPowerHandlesEmptyServer() {
        BotTuning tuning = tuning();
        assertThat(tuning.targetPower(0L, FixedPoint.SCALE)).isZero();
        assertThat(tuning.powerRatioFixed(5000L, 0L)).as("-1 表示「比值无定义」，与「比值为 0」区分开")
                .isEqualTo(-1L);
        assertThat(tuning.passesPowerCheck(5000L, 0L)).as("没有可比较对象不算违规").isTrue();
    }

    @Test
    @DisplayName("战力系数上界超过 1.0 在构造期就被拒绝：Bot 强过真人均值会让圈层规则失去意义")
    void tuningRejectsPowerRatioAboveOne() {
        assertThatThrownBy(() -> new BotTuning.Rules(FixedPoint.parse("0.80"), FixedPoint.parse("1.20"),
                FixedPoint.parse("0.70"), FixedPoint.parse("1.30"), 3, 5000,
                FixedPoint.parse("20.0"), FixedPoint.parse("5.0"), FixedPoint.parse("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1.0");
    }

    @Test
    @DisplayName("验收容忍带必须覆盖校准目标带，否则按目标校准出的 Bot 通不过自己的验收")
    void checkBandMustCoverCalibrationBand() {
        assertThatThrownBy(() -> new BotTuning.Rules(FixedPoint.parse("0.80"), FixedPoint.parse("0.95"),
                FixedPoint.parse("0.85"), FixedPoint.parse("0.90"), 3, 5000,
                FixedPoint.parse("20.0"), FixedPoint.parse("5.0"), FixedPoint.parse("1.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须覆盖");
    }

    // ---------- 攻击频控（验收 4） ----------

    @Test
    @DisplayName("验收4：同一真人 24h 内被 Bot 攻击不超过 3 次，第 4 次被拒")
    void attackFrequencyIsThrottled() {
        BotTuning tuning = tuning();
        long now = 1_000_000L;
        for (int i = 0; i < 3; i++) {
            assertThat(tuning.mayAttack("victim", now + i)).as("第 %d 次应当放行", i + 1).isTrue();
            tuning.recordAttack("victim", now + i);
        }
        assertThat(tuning.mayAttack("victim", now + 10)).as("第 4 次必须被拒").isFalse();
        assertThat(tuning.attacksIn24h("victim", now + 10)).isEqualTo(3);

        // 24 小时之后配额恢复
        assertThat(tuning.mayAttack("victim", now + DAY + 1)).isTrue();
        // 别的受害者不受影响
        assertThat(tuning.mayAttack("other", now)).isTrue();
    }

    @Test
    @DisplayName("判定与记账分开：mayAttack 只读，不会把配额吃掉一次")
    void mayAttackDoesNotConsumeQuota() {
        BotTuning tuning = tuning();
        long now = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            assertThat(tuning.mayAttack("victim", now)).isTrue();
        }
        assertThat(tuning.attacksIn24h("victim", now)).as("查十次不该扣一次配额").isZero();
    }

    @Test
    @DisplayName("频控表可回收：超过 24h 的记录被清掉，否则这张表只增不减")
    void attackLogIsEvictable() {
        BotTuning tuning = tuning();
        tuning.recordAttack("old", 1_000L);
        tuning.recordAttack("fresh", DAY * 10);
        tuning.evict(DAY * 10 + HOUR);
        assertThat(tuning.attacksIn24h("old", DAY * 10 + HOUR)).isZero();
        assertThat(tuning.mayAttack("old", DAY * 10 + HOUR)).isTrue();
    }

    // ---------- 密度调节（§五） ----------

    @Test
    @DisplayName("密度曲线随天数递减，且在 D1/D7/D30 三点上取到配置值")
    void densityCurveDecreasesOverTime() {
        BotTuning tuning = tuning();
        assertThat(tuning.densityAt(0)).isEqualTo(FixedPoint.parse("20.0"));
        assertThat(tuning.densityAt(7)).isEqualTo(FixedPoint.parse("5.0"));
        assertThat(tuning.densityAt(30)).isEqualTo(FixedPoint.parse("1.5"));
        assertThat(tuning.densityAt(100)).as("D30 之后进入稳态").isEqualTo(FixedPoint.parse("1.5"));
        assertThat(tuning.densityAt(3)).isBetween(FixedPoint.parse("5.0"), FixedPoint.parse("20.0"));
    }

    @Test
    @DisplayName("Bot 目标数按密度算并被单服上限夹住；真人为 0 时退回下限（新服地图不能是空的）")
    void targetBotCountIsClamped() {
        BotTuning tuning = tuning();
        // floor 取 10：取 200 会把 D1 的密度计算结果盖住，测不到曲线本身
        assertThat(tuning.targetBotCount(1, 0, 10)).as("D1 一个真人 × 20 = 20 个 Bot").isEqualTo(20);
        assertThat(tuning.targetBotCount(0, 0, 200)).as("没有真人时退回下限：新服第一张地图不能是空的")
                .isEqualTo(200);
        assertThat(tuning.targetBotCount(1000, 0, 10))
                .as("1000 × 20 = 20000 超出单服上限 5000").isEqualTo(5000);
        assertThat(tuning.targetBotCount(100, 30, 200)).as("D30 密度 1.5 ⇒ 150，但不得低于下限 200")
                .isEqualTo(200);
        assertThat(tuning.targetBotCount(10, 7, 10)).as("D7 密度 5 ⇒ 50").isEqualTo(50);
    }

    @Test
    @DisplayName("密度曲线递增（Bot 越来越多）在构造期就被拒绝")
    void tuningRejectsIncreasingDensity() {
        assertThatThrownBy(() -> new BotTuning.Rules(FixedPoint.parse("0.80"), FixedPoint.parse("0.95"),
                FixedPoint.parse("0.70"), FixedPoint.parse("1.00"), 3, 5000,
                FixedPoint.parse("1.5"), FixedPoint.parse("5.0"), FixedPoint.parse("20.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须随时间递减");
    }

    // ---------- 合规红线（§六 / §七，验收 5） ----------

    @Test
    @DisplayName("验收5：Bot 不得任盟主、不得任任何官职；小队队长位同样不给")
    void botsNeverHoldOffice() {
        BotTuning tuning = tuning();
        assertThat(tuning.mayHoldOffice("SQUAD", false, false)).as("小队普通成员可以").isTrue();
        assertThat(tuning.mayHoldOffice("SQUAD", true, false))
                .as("Bot 队长会替真人做「踢谁」这种组织决定").isFalse();
        assertThat(tuning.mayHoldOffice("ALLIANCE", false, false)).as("联盟普通成员可以").isTrue();
        assertThat(tuning.mayHoldOffice("ALLIANCE", true, false)).as("§七 红线：不得任盟主").isFalse();
        assertThat(tuning.mayHoldOffice("ALLIANCE", false, true)).as("副盟主/长老也是官职").isFalse();
        assertThat(tuning.mayHoldOffice("NATION", false, false)).as("§六：国家只能补位普通成员").isTrue();
        assertThat(tuning.mayHoldOffice("NATION", false, true)).as("§七 红线：不得任任何官职").isFalse();
        assertThat(tuning.mayHoldOffice("NATION", true, false)).isFalse();
        assertThat(tuning.mayHoldOffice(null, false, false)).isFalse();
        assertThat(tuning.mayHoldOffice("UNKNOWN", false, false)).as("未知层级默认拒绝").isFalse();
    }

    @Test
    @DisplayName("验收5：Bot 不得占据需真人竞争的前 3 名排行奖励坑位")
    void botsNeverTakeTopRankRewards() {
        BotTuning tuning = tuning();
        for (int rank = 1; rank <= 3; rank++) {
            assertThat(tuning.mayEnterRankTop(rank, 3)).as("第 %d 名有奖励，Bot 不得占", rank).isFalse();
        }
        assertThat(tuning.mayEnterRankTop(4, 3)).isTrue();
        assertThat(tuning.mayEnterRankTop(100, 3)).isTrue();
        assertThatThrownBy(() -> tuning.mayEnterRankTop(0, 3))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("名次从 1 起");
    }

    @Test
    @DisplayName("验收5：Bot 场景白名单默认拒绝 —— 付费弹窗、限时礼包、官方公告、私聊真人一律不放行")
    void botScenesAreWhitelisted() {
        BotTuning tuning = tuning();
        for (String allowed : List.of("WORLD_MAP", "ALLIANCE_CHAT", "SQUAD_CHAT", "WORLD_CHAT",
                "RALLY", "BATTLE", "SCOUT_REPORT")) {
            assertThat(tuning.mayAppearIn(allowed)).as("%s 应当放行", allowed).isTrue();
        }
        for (String forbidden : List.of("PAYMENT_POPUP", "LIMITED_PACK_COUNTDOWN",
                "OFFICIAL_ANNOUNCEMENT", "CUSTOMER_SERVICE", "PRIVATE_CHAT_TO_HUMAN")) {
            assertThat(tuning.mayAppearIn(forbidden)).as("§七 红线：%s 必须拒绝", forbidden).isFalse();
        }
        // 默认拒绝：新增一个付费场景时若忘了登记，它自动落在拒绝侧
        assertThat(tuning.mayAppearIn("SOME_NEW_PAYMENT_SCENE")).isFalse();
        assertThat(tuning.mayAppearIn(null)).isFalse();
    }

    // ---------- 孵化模板（B11 §一 × §四 成长参差） ----------

    private static BotArchetype paoyao() {
        return new BotArchetype("bot_paoyao", "陪跑者",
                FixedPoint.parse("0.30"), FixedPoint.parse("0.05"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.80"), FixedPoint.parse("0.75"), FixedPoint.parse("0.21"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.90"), 20, 30,
                FixedPoint.parse("0.15"), FixedPoint.parse("0.20"), List.of(8, 9, 12, 20, 21));
    }

    @Test
    @DisplayName("孵化：区间内独立取值（成长参差），同一种子可复现，四个维度都来自原型")
    void spawnProfileDrawsWithinRanges() {
        BotArchetype archetype = paoyao();
        Rng first = Rng.of(42L);
        Rng replay = Rng.of(42L);
        Set<Long> growths = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            BotProfile profile = archetype.spawnProfile("P" + i, 1L, first);
            assertThat(archetype.spawnProfile("P" + i, 1L, replay))
                    .as("同一个种子重跑必须得到同一份画像（铁律 4：随机可复现）").isEqualTo(profile);
            assertThat(profile.archetypeId()).isEqualTo("bot_paoyao");
            assertThat(profile.ai().aggression()).isEqualTo(FixedPoint.parse("0.05"));
            assertThat(profile.ai().greed()).isEqualTo(FixedPoint.parse("0.75"));
            assertThat(profile.ai().sociability()).isEqualTo(FixedPoint.parse("0.20"));
            assertThat(profile.growthFactorFixed())
                    .isBetween(FixedPoint.parse("0.70"), FixedPoint.parse("0.90"));
            assertThat(profile.persona().reactionDelayMinSec()).isBetween(20L, 30L);
            assertThat(profile.persona().reactionDelayMaxSec())
                    .isBetween(profile.persona().reactionDelayMinSec(), 30L);
            assertThat(profile.persona().mistakeRateFixed())
                    .isBetween(FixedPoint.parse("0.15"), FixedPoint.parse("0.20"));
            assertThat(profile.persona().activeHours()).containsExactly(8, 9, 12, 20, 21);
            growths.add(profile.growthFactorFixed());
        }
        assertThat(growths.size())
                .as("100 个同原型的 Bot 不该只有一两种成长系数：区间是给「成长参差」用的（§四 禁止项）")
                .isGreaterThan(50);
    }

    @Test
    @DisplayName("孵化模板：非法区间当场拒绝（反应延迟越界、占比为 0、战力系数为 0）")
    void archetypeRejectsInvalidRanges() {
        assertThatThrownBy(() -> new BotArchetype("bot_x", "坏原型",
                FixedPoint.parse("0.30"), FixedPoint.parse("0.05"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.80"), FixedPoint.parse("0.75"), FixedPoint.parse("0.21"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.90"), 0, 30,
                FixedPoint.parse("0.15"), FixedPoint.parse("0.20"), List.of(8)))
                .as("反应延迟下界 0 秒等于 0ms 响应，验收 9 明令禁止")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("反应延迟");
        assertThatThrownBy(() -> new BotArchetype("bot_x", "坏原型",
                FixedPoint.parse("0"), FixedPoint.parse("0.05"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0.80"), FixedPoint.parse("0.75"), FixedPoint.parse("0.21"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.90"), 5, 10,
                FixedPoint.parse("0.15"), FixedPoint.parse("0.20"), List.of(8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("share");
        assertThatThrownBy(() -> new BotArchetype("bot_x", "坏原型",
                FixedPoint.parse("0.30"), FixedPoint.parse("0.05"), FixedPoint.parse("0.20"),
                FixedPoint.parse("0"), FixedPoint.parse("0.75"), FixedPoint.parse("0.21"),
                FixedPoint.parse("0.70"), FixedPoint.parse("0.90"), 5, 10,
                FixedPoint.parse("0.15"), FixedPoint.parse("0.20"), List.of(8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("powerFactor");
    }
}
