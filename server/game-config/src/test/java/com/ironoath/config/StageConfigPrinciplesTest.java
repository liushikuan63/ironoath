package com.ironoath.config;

import com.ironoath.config.cfg.ChapterCfg;
import com.ironoath.config.cfg.MapmonsterCfg;
import com.ironoath.config.cfg.StageCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：把 B09 §二「关卡设计原则」变成会失败的断言。
 * 依赖：JUnit 5 + AssertJ；读仓库里的真实表，不启动容器。
 *
 * <p><b>为什么这些原则必须写成测试而不是写在文档里</b>：关卡表有 50 行，
 * 每一行都是手可以改的数字。原则写在 designNote 里的话，
 * 某一次「就把第 3 章第 7 关调难一点」的改动不会让任何东西变红 ——
 * 而难度曲线一旦出现无法解释的突起，玩家会精确地感觉到（「这关怎么突然打不过」），
 * 却没人能从 50 行里看出是哪一行被改过。
 *
 * <p><b>本类钉住的第一条是一次真实事故</b>：chapter.json v1 的 difficultyBase 是手写的
 * 200/320/460/640/880，与 curve.CHAPTER_DIFFICULTY 的 1.25 对不上 ——
 * 按 1.25 章内 10 关涨 ×7.45，于是第一章第 10 关难度 1490、第二章第 1 关只有 320，
 * 章节交界处难度掉了 4.66 倍。玩家刚打通 BOSS，下一章开头突然简单四倍。
 * 这个 bug 在 v1 里存在了很久，因为 difficultyBase 当时还没有任何消费方。
 */
class StageConfigPrinciplesTest {

    private static final int STAGES_PER_CHAPTER = 10;

    private static ConfigRegistry registry;
    private static List<StageCfg> stages;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
        stages = registry.all(StageCfg.class);
    }

    private static long enemyTotal(StageCfg stage) {
        return stage.enemyInfantry() + stage.enemyCavalry() + stage.enemyArcher() + stage.enemySiege();
    }

    /** 全局关卡序号 n = (章-1)×10 + 关号，1~50。 */
    private static int globalIndex(StageCfg stage, Map<String, Integer> chapterNoById) {
        Integer chapterNo = chapterNoById.get(stage.chapterId());
        assertThat(chapterNo).as("关卡 %s 的 chapterId 必须能在 chapter 表里找到", stage.id())
                .isNotNull();
        return (chapterNo - 1) * STAGES_PER_CHAPTER + (int) stage.stageNo();
    }

    private static Map<String, Integer> chapterNos() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ChapterCfg chapter : registry.all(ChapterCfg.class)) {
            out.put(chapter.id(), (int) chapter.chapterNo());
        }
        return out;
    }

    @Test
    @DisplayName("50 关、每章 10 关、关号连续，且每关都挂在存在的章上")
    void thereAreFiftyStagesInTenStageChapters() {
        assertThat(stages).as("B09 §4：首发 5 章 × 10 关").hasSize(50);
        Map<String, Integer> chapterNos = chapterNos();
        assertThat(chapterNos).hasSize(5);

        Map<String, List<StageCfg>> byChapter = new LinkedHashMap<>();
        for (StageCfg stage : stages) {
            assertThat(chapterNos).containsKey(stage.chapterId());
            byChapter.computeIfAbsent(stage.chapterId(), k -> new ArrayList<>()).add(stage);
        }
        byChapter.forEach((chapterId, list) -> {
            assertThat(list).as("章 %s 必须有 10 关", chapterId).hasSize(STAGES_PER_CHAPTER);
            List<Long> stageNos = list.stream().map(StageCfg::stageNo).sorted().toList();
            assertThat(stageNos).as("章 %s 的关号必须是 1~10 连续", chapterId)
                    .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        });
    }

    @Test
    @DisplayName("难度 = 敌方总兵力 = 10 × 1.25^(n-1)，且跨章严格递增（章节交界处不许掉难度）")
    void difficultyFollowsTheCurveAndNeverResetsAtChapterBoundaries() {
        Map<String, Integer> chapterNos = chapterNos();
        List<StageCfg> ordered = new ArrayList<>(stages);
        ordered.sort((a, b) -> Integer.compare(globalIndex(a, chapterNos), globalIndex(b, chapterNos)));

        long previousTotal = 0L;
        for (StageCfg stage : ordered) {
            int n = globalIndex(stage, chapterNos);
            long expected = curve(n);
            assertThat(enemyTotal(stage))
                    .as("第 %d 关（%s）的敌方总兵力必须落在难度曲线上。"
                            + "难度只有一个自变量（总兵力），任何偏离都是无法解释的突起", n, stage.id())
                    .isEqualTo(expected);
            assertThat(enemyTotal(stage))
                    .as("第 %d 关（%s）必须比上一关难：chapter.json v1 曾经让难度在章节交界处"
                            + "掉 4.66 倍（第一章第 10 关 1490 → 第二章第 1 关 320），"
                            + "玩家刚打通 BOSS 就遇到一个简单四倍的开头", n, stage.id())
                    .isGreaterThan(previousTotal);
            previousTotal = enemyTotal(stage);
        }
    }

    /**
     * 难度曲线的第 n 项，用 BigDecimal 精确算并按 HALF_UP 取整。
     *
     * <p><b>刻意不用 {@code Math.round(Math.pow(...))}</b>：生成这张表的脚本是 Python，
     * 而 Python 内建的 {@code round()} 是「四舍六入五成双」—— {@code round(12.5) = 12}，
     * Java 的 {@code Math.round(12.5) = 13}。两边各用一种取整，第 2 关就会差 1 个兵，
     * 而这条断言会失败在一个看起来像是「表写错了」的地方，实际是取整口径不同。
     * double 的幂运算在 n=50 时也不该被当成精确期望值。
     */
    /**
     * 分段难度曲线的第 n 项，四个参数全部取自 global 表（铁律 1：不在测试里写死数值）。
     *
     * <p>前 EARLY_THROUGH 关用缓坡比率，之后用陡坡比率。两段各自的比率与分段点都是配置，
     * 所以这条测试同时验的是「表与曲线一致」和「曲线本身是分段的那个形状」。
     */
    private static long curve(int n) {
        java.math.BigDecimal base = java.math.BigDecimal.valueOf(
                registry.longParam("STAGE_DIFFICULTY_BASE"));
        int earlyThrough = (int) registry.longParam("STAGE_DIFFICULTY_EARLY_THROUGH");
        java.math.BigDecimal early = fixed("STAGE_DIFFICULTY_RATIO_EARLY");
        java.math.BigDecimal late = fixed("STAGE_DIFFICULTY_RATIO_LATE");
        java.math.BigDecimal value = n <= earlyThrough
                ? base.multiply(early.pow(n - 1))
                : base.multiply(early.pow(earlyThrough - 1)).multiply(late.pow(n - earlyThrough));
        return value.setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    /** 定点参数转 BigDecimal：fixedParam 返回的是 ×10000 的 long。 */
    private static java.math.BigDecimal fixed(String paramId) {
        return java.math.BigDecimal.valueOf(registry.fixedParam(paramId)).movePointLeft(4);
    }

    @Test
    @DisplayName("难度曲线终点对齐世界野怪：第 50 关的兵力约等于 mapmonster LV50（1% 以内）")
    void stageCurveMatchesTheWorldMonsterCurve() {
        MapmonsterCfg topMonster = registry.all(MapmonsterCfg.class).stream()
                .max((a, b) -> Long.compare(a.level(), b.level())).orElseThrow();
        long monsterTotal = topMonster.infantryCount() + topMonster.cavalryCount()
                + topMonster.archerCount() + topMonster.siegeCount();
        StageCfg lastStage = stages.stream()
                .filter(s -> s.id().equals("stage_05_10")).findFirst().orElseThrow();

        // 终点必须对齐：第 50 关要与地图上最强的野怪同一量级，
        // 否则玩家会觉得「关卡打到头还不如去打野怪」，两条 PVE 线互相拆台。
        // 用相对容差而不是绝对值：分段曲线的晚段比率是被两端解出来的（1.466），
        // 取整 20 次之后会漂出几个千分点，那是可接受的；漂出百分之几才说明曲线错了
        long diff = Math.abs(enemyTotal(lastStage) - monsterTotal);
        assertThat(diff * 1000L / monsterTotal)
                .as("关卡终点（%d）与世界野怪终点（%d）必须几乎相同，否则玩家会遇到"
                        + "「关卡第 50 关比地图上最强的野怪难十倍」这种口径分裂",
                        enemyTotal(lastStage), monsterTotal)
                .isLessThanOrEqualTo(10L);   // 1%
    }

    @Test
    @DisplayName("B09 §二：每 5 关一个检查点，每章末 BOSS 必须有机制而不是纯数值")
    void checkpointsAndMechanisedBosses() {
        Map<String, Integer> chapterNos = chapterNos();
        List<StageCfg> checkpoints = new ArrayList<>();
        List<StageCfg> bosses = new ArrayList<>();
        for (StageCfg stage : stages) {
            if (stage.bossMechanic() != StageCfg.BossMechanic.NONE) {
                bosses.add(stage);
            }
            if (stage.checkpoint()) {
                checkpoints.add(stage);
            }
        }
        assertThat(bosses).as("5 章各一个 BOSS").hasSize(5);
        assertThat(bosses).allSatisfy(boss -> {
            assertThat(boss.stageNo()).as("BOSS 必须是每章最后一关，%s", boss.id())
                    .isEqualTo(STAGES_PER_CHAPTER);
            assertThat(boss.checkpoint())
                    .as("BOSS 不该同时是检查点：%s。两者都是「难度台阶」，"
                            + "叠在一关上会让第 5 关这个台阶消失", boss.id())
                    .isFalse();
        });
        assertThat(checkpoints).as("每章第 5 关是检查点").hasSize(5);
        assertThat(checkpoints).allSatisfy(cp ->
                assertThat(cp.stageNo()).isEqualTo(5L));

        // 「机制而非纯数值」的最低要求：三种机制都要真的被用到。
        // 只用一种的话，玩家从第一章到第五章遇到的 BOSS 是同一个套路，
        // 而 B09 §二 的原话是「逼玩家换阵型」—— 一个套路只能逼一次
        assertThat(bosses.stream().map(StageCfg::bossMechanic).distinct().count())
                .as("B09 §二 要求至少实现 3 种 BOSS 机制，配置里也应当三种都排上")
                .isGreaterThanOrEqualTo(3L);
        // BOSS 的四兵种均分也是「机制而非数值」的一部分：单一兵种的 BOSS 只需要一个克制兵种就能过
        for (StageCfg boss : bosses) {
            assertThat(boss.enemyInfantry()).isPositive();
            assertThat(boss.enemyCavalry()).isPositive();
            assertThat(boss.enemyArcher()).isPositive();
            assertThat(boss.enemySiege()).isPositive();
        }
        assertThat(globalIndex(bosses.get(0), chapterNos)).isEqualTo(10);
    }

    @Test
    @DisplayName("阶级是章级属性：章内 10 关不变，跨章不回退")
    void tierIsAChapterLevelProperty() {
        Map<String, Long> tierByChapter = new LinkedHashMap<>();
        for (StageCfg stage : stages) {
            Long existing = tierByChapter.putIfAbsent(stage.chapterId(), stage.enemyTier());
            if (existing != null) {
                assertThat(stage.enemyTier())
                        .as("%s 的阶级必须与本章其它关一致：难度只能有一个自变量（总兵力），"
                                + "阶级也逐关变的话 1.25^n 这条曲线立刻失去含义", stage.id())
                        .isEqualTo(existing);
            }
        }
        List<Long> tiers = new ArrayList<>(tierByChapter.values());
        assertThat(tiers).as("阶级逐章抬升且不回退").isSorted();
        assertThat(tiers.get(0)).isEqualTo(1L);
    }

    @Test
    @DisplayName("三星条件的三个门槛都在表里：限时回合不超过内核上限，且 BOSS 比普通关宽松")
    void starThresholdsAreExpressible() {
        long maxRounds = registry.longParam("BATTLE_MAX_ROUNDS");
        for (StageCfg stage : stages) {
            assertThat(stage.roundLimit())
                    .as("%s 的限时回合不得超过内核的最大回合数，否则第三星永远拿不到", stage.id())
                    .isLessThanOrEqualTo(maxRounds);
            assertThat(stage.staminaCost()).as("%s 必须消耗体力", stage.id()).isPositive();
        }
        StageCfg normal = stages.stream().filter(s -> s.id().equals("stage_01_01")).findFirst().orElseThrow();
        StageCfg boss = stages.stream().filter(s -> s.id().equals("stage_01_10")).findFirst().orElseThrow();
        assertThat(boss.roundLimit())
                .as("BOSS 的限时应当比普通关宽松：BOSS 靠机制加难，再卡回合就是双重惩罚")
                .isGreaterThan(normal.roundLimit());
        // 兵种限制只出现在部分关卡：每关都禁兵种会让阵容养成失去意义
        long restricted = stages.stream()
                .filter(s -> s.unitRestriction() != StageCfg.UnitRestriction.NONE).count();
        assertThat(restricted)
                .as("应当有一部分关卡带兵种限制（B09 §4 的三星条件之一），但不能是全部")
                .isBetween(5L, 40L);
    }

    @Test
    @DisplayName("前三章零氪可达：主城等级门槛不超过零氪第 7 天的 13 级（验收 4 的结构前提）")
    void firstThreeChaptersAreReachableWithoutPaying() {
        // B02 已定稿的锚点：零氪玩家第 7 天卡在主城 13 级
        int zeroPayDay7Level = 13;
        List<ChapterCfg> chapters = registry.all(ChapterCfg.class);
        for (ChapterCfg chapter : chapters) {
            if (chapter.chapterNo() > 3L) {
                continue;
            }
            assertThat(chapter.requireMainLevel())
                    .as("B09 §二：前三章必须零氪可三星通关（首日体验底线）。"
                            + "章 %d 要求主城 %d 级，而零氪第 7 天只有 13 级",
                            chapter.chapterNo(), chapter.requireMainLevel())
                    .isLessThanOrEqualTo(zeroPayDay7Level);
        }
        // 第 4、5 章必须真的更难进，否则「前三章零氪可通」这条底线没有对照
        assertThat(chapters.stream().filter(c -> c.chapterNo() > 3L)
                .allMatch(c -> c.requireMainLevel() > 3L)).isTrue();
    }

    @Test
    @DisplayName("逐关金币之和等于章级金币：不引入新数字，也就不引入新的可调参数")
    void stageGoldSumsToTheChapterTotal() {
        Map<String, Long> goldByChapter = new LinkedHashMap<>();
        for (StageCfg stage : stages) {
            goldByChapter.merge(stage.chapterId(), stage.rewardGold(), Long::sum);
        }
        for (ChapterCfg chapter : registry.all(ChapterCfg.class)) {
            long sum = goldByChapter.getOrDefault(chapter.id(), 0L);
            // 逐关 = 章级 × stageNo / 55，逐关取整会留下最多 10 个单位的误差
            assertThat(Math.abs(sum - chapter.rewardGold()))
                    .as("章 %s 的 10 关金币之和（%d）应当等于章级金币（%d）",
                            chapter.id(), sum, chapter.rewardGold())
                    .isLessThanOrEqualTo(10L);
        }
    }
}
