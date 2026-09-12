package com.ironoath.config;

import com.ironoath.config.cfg.ChapterCfg;
import com.ironoath.config.cfg.StageCfg;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 职责：B09 验收 4「零氪可通前三章」的可执行形式。
 * 依赖：JUnit 5 + AssertJ；读真实的 stage / chapter 表。
 *
 * <p><b>这条验收曾经不成立，本类当时是 {@code @Disabled} 的</b>。
 * 用「难度 = 敌方总兵力」这个口径一量就露出来了：单一 1.25 曲线之下第 30 关是 6462 兵，
 * 而零氪首日的带兵上限只有 850、第 7 天 1513 —— 第三章是一堵墙。
 * 修法是放弃 B00 的单一几何曲线，改成分段（前 30 关 1.12、后 20 关 1.466），
 * 于是第 30 关变成 267 兵 ≈ 首日上限的三分之一。四条约束里放弃的是「单一几何曲线」，
 * 因为另外三条都有独立的验收标准或已定稿的锚点在支撑。完整推导见 chapter.json 的 designNote。
 *
 * <p><b>解禁之后本类钉住的是两条会互相牵制的约束</b>：
 * 前三章的敌方兵力不得超过零氪首日的带兵上限（本类），
 * 且第 50 关必须与 mapmonster LV50 对齐（{@code StageConfigPrinciplesTest}）。
 * 这次的教训正是这两条各自都自洽、合起来不成立，而没有任何一处会报错 ——
 * 所以两条都必须有断言，缺一条另一条就会被悄悄改坏。
 *
 * <p><b>「敌方兵力 ≤ 带兵上限」只是必要条件，不是充分条件</b>：
 * 三星还要求「无损」（己方阵亡为 0），而这个内核里攻方损失 ≈ 敌方总兵力 / LANCHESTER_K，
 * 所以敌方超过约 17 兵时阵亡就不为 0 —— 必须靠 HEAL 技能把损失压回去。
 * 也就是说前三章的三星门槛从「数值」变成了「阵容」（要不要带治疗武将），
 * 这正是 B09 §二 想要的：卡住玩家的原因应当是阵型不对而不是练度不够，
 * 因为前者的答案是换兵种（免费），后者的答案是充钱。
 * 充分性需要真的跑一遍带治疗武将的战斗，那属于验收 4 的「脚本」部分，尚未交付。
 */
class StageZeroPayAcceptanceTest {

    private static ConfigRegistry registry;

    @BeforeAll
    static void loadRealConfig() {
        registry = ConfigRegistry.loadFromDirectory(Path.of("contract/config"));
    }

    private static long enemyTotal(StageCfg stage) {
        return stage.enemyInfantry() + stage.enemyCavalry() + stage.enemyArcher() + stage.enemySiege();
    }

    /**
     * 零氪首日的带兵上限（实测 850），推导见类注释。
     *
     * <p>= TROOP_PER_COMMAND(5) × 队伍统帅(170)，队伍统帅 = 主将 SSR 统率 100
     * + 2 × 副将 SR 统率 70 × HERO_SUB_BONUS_RATIO(0.5)，武将均为 1 级。
     * 「主 SSR / 副 SR」是 B02 已定稿的零氪武将画像：SSR 靠碎片合成（新手池有 UP），
     * SR 是副将位能拿到的最好档位。
     *
     * <p><b>刻意不在用例里重新推一遍</b>：推导要读三个参数并做定点→浮点换算，
     * 任何一处参数类型判断错了，这条用例就会以一个「看起来像数值问题」的假失败收场，
     * 反而掩盖它本来要暴露的那个真矛盾。这三个参数若被改动，本常数必须跟着重新量 ——
     * 而那种改动本来就应该触发一次验收 4 的重新评估。
     */
    private static final long DAY_ONE_TROOP_CAP = 850L;

    /** 零氪第 7 天的带兵上限（实测 1513，武将 40 级，主城 13 级）。 */
    private static final long DAY_SEVEN_TROOP_CAP = 1513L;

    @Test
    @DisplayName("验收4：前三章零氪首日可三星 —— 每关的敌方兵力都必须在首日带兵上限的可胜范围内")
    void firstThreeChaptersAreThreeStarableOnDayOneWithoutPaying() {
        long dayOneCap = DAY_ONE_TROOP_CAP;

        List<ChapterCfg> chapters = registry.all(ChapterCfg.class).stream()
                .filter(c -> c.chapterNo() <= 3L).toList();
        assertThat(chapters).hasSize(3);

        for (StageCfg stage : registry.all(StageCfg.class)) {
            boolean inFirstThree = chapters.stream().anyMatch(c -> c.id().equals(stage.chapterId()));
            if (!inFirstThree) {
                continue;
            }
            long enemy = enemyTotal(stage);
            // 三星要同时满足「通关」与「无损」。无损要求己方阵亡为 0，
            // 而这个内核里攻方损失 ≈ 敌方总兵力 / LANCHESTER_K，
            // 所以敌方兵力必须小到「损失 × PVE死亡比例(0.20) 取整后为 0」，
            // 即敌方总兵力 < LANCHESTER_K / 0.20 / 2 ≈ K×2.5。
            // 同时兵力要足以在 roundLimit 回合内取胜，所以还有一个下限。
            // 这里只用「敌方兵力不得超过首日带兵上限」这一条最宽松的必要条件，
            // 连它都不满足时，通关本身就无从谈起
            assertThat(enemy)
                    .as("%s（第 %d 关，阶级 T%d）的敌方兵力不得超过零氪首日带兵上限 %d，"
                            + "否则「前三章首日零氪可三星」直接不成立。第 7 天的上限也只有 %d",
                            stage.id(), stage.stageNo(), stage.enemyTier(), dayOneCap,
                            DAY_SEVEN_TROOP_CAP)
                    .isLessThanOrEqualTo(dayOneCap);
        }
    }
}
