package com.ironoath.core.world;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchCalculator;
import com.ironoath.core.scout.ScoutReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：B07 的纯逻辑验证 —— 坐标与 chunk、确定性世界生成、迷雾、行军数值与状态机、侦查误差。
 * 依赖：纯 JUnit，不需要容器、不需要配置表（铁律 2）。
 *
 * <p>覆盖 B07 验收 7（召回时间 = 已行军距离 / 速度、兵力零损失）与验收 8
 * （侦查误差随等级差放大、且严格落在配置区间内，1000 次采样）。
 * 验收 1/2/5/6/11 在 game-web 的 {@code WorldEndpointTest} 与 CI 静态检查里。
 */
class WorldCoreTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    /** 与 global.json 一致的规则：512 世界、32 chunk、9 块视口、20KB payload、2%/3% 密度、32 格一级。 */
    private static WorldGenerator.Rules rules() {
        return new WorldGenerator.Rules(512, 32, 9, 20480L,
                FixedPoint.parse("0.02"), FixedPoint.parse("0.03"), 32, 16, 20260906L);
    }

    private static WorldGenerator.Catalog catalog() {
        return new WorldGenerator.Catalog(
                List.of("mapmonster_lv01", "mapmonster_lv02", "mapmonster_lv03", "mapmonster_lv04"),
                List.of("WOOD", "STONE", "IRON", "GRAIN"));
    }

    // ---------- 坐标与 chunk ----------

    @Test
    @DisplayName("chunk 键按位移计算，同块内所有格子得到同一个键，跨块得到不同键")
    void chunkKeyGroupsCellsCorrectly() {
        assertThat(Coord.of(0, 0).chunkKey(32)).isEqualTo("0:0");
        assertThat(Coord.of(31, 31).chunkKey(32)).as("31 仍在第 0 块").isEqualTo("0:0");
        assertThat(Coord.of(32, 0).chunkKey(32)).as("32 进入第 1 块").isEqualTo("1:0");
        assertThat(Coord.of(511, 511).chunkKey(32)).isEqualTo("15:15");
        assertThat(Coord.chunkOrigin("3:7", 32)).isEqualTo(Coord.of(96, 224));
    }

    @Test
    @DisplayName("chunk 尺寸不是 2 的幂时拒绝：位移与除法在取整上不等价，算错会让相邻格子进不同的块")
    void chunkSizeMustBePowerOfTwo() {
        assertThatThrownBy(() -> Coord.of(1, 1).chunkKey(30))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 的幂");
        assertThatThrownBy(() -> new WorldGenerator.Rules(512, 30, 9, 20480L, 200L, 300L, 32, 16, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 的幂");
        assertThatThrownBy(() -> new WorldGenerator.Rules(500, 32, 9, 20480L, 200L, 300L, 32, 16, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("整除");
    }

    @Test
    @DisplayName("距离用曼哈顿（整数、无误差）；环数用切比雪夫（方形环便于手算等级）")
    void distancesAreIntegerBased() {
        assertThat(Coord.of(0, 0).distanceTo(Coord.of(3, 4))).isEqualTo(7);
        assertThat(Coord.of(10, 10).distanceTo(Coord.of(10, 10))).isZero();
        // 全图最大曼哈顿距离 = 511 + 511 = 1022，这是 MARCH_SECONDS_PER_TILE 校准的基准
        assertThat(Coord.of(0, 0).distanceTo(Coord.of(511, 511))).isEqualTo(1022);
        assertThat(Coord.of(0, 0).ringOf(Coord.of(256, 256))).isEqualTo(256);
        assertThat(Coord.of(270, 250).ringOf(Coord.of(256, 256))).as("切比雪夫取两轴较大者").isEqualTo(14);
    }

    @Test
    @DisplayName("视口是 3×3 共 9 块，行优先稳定顺序；世界边缘不会返回负坐标块")
    void viewportIsThreeByThree() {
        Collection<String> chunks = FogOfWar.viewportChunks(Coord.of(100, 100), 32, 9);
        assertThat(chunks).hasSize(9);
        assertThat(chunks).contains("3:3", "2:2", "4:4", "2:3", "3:2", "4:3", "3:4", "2:4", "4:2");

        // 左上角：cx-1 / cy-1 会是负数，必须被跳过而不是返回 "-1:3"
        Collection<String> corner = FogOfWar.viewportChunks(Coord.of(0, 100), 32, 9);
        assertThat(corner).hasSize(6);
        assertThat(corner).noneMatch(k -> k.startsWith("-"));

        assertThatThrownBy(() -> FogOfWar.viewportChunks(Coord.of(0, 0), 32, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .as("非完全平方数意味着视口不是正方形，客户端的九宫格布局无从对齐")
                .hasMessageContaining("完全平方数");
    }

    // ---------- 确定性世界生成 ----------

    @Test
    @DisplayName("世界生成是确定性的：同一 (seed, 坐标) 永远得到同一结果，且不依赖调用顺序")
    void worldGenerationIsDeterministic() {
        WorldGenerator.Rules rules = rules();
        WorldGenerator.Catalog catalog = catalog();
        for (int i = 0; i < 200; i++) {
            Coord coord = Coord.of(i * 3 % 512, i * 7 % 512);
            WorldGenerator.Cell first = WorldGenerator.generate(rules, catalog, coord);
            // 中间穿插别的坐标：如果生成器有任何跨调用的状态，这里就会暴露
            WorldGenerator.generate(rules, catalog, Coord.of((i + 1) * 11 % 512, (i + 5) % 512));
            WorldGenerator.Cell second = WorldGenerator.generate(rules, catalog, coord);
            assertThat(second).as("坐标 %s 的生成结果必须与调用顺序无关", coord).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("换种子世界就变：证明生成真的走了随机而不是每次都返回同一份")
    void differentSeedProducesDifferentWorld() {
        WorldGenerator.Catalog catalog = catalog();
        WorldGenerator.Rules a = new WorldGenerator.Rules(512, 32, 9, 20480L,
                FixedPoint.parse("0.02"), FixedPoint.parse("0.03"), 32, 16, 1L);
        WorldGenerator.Rules b = new WorldGenerator.Rules(512, 32, 9, 20480L,
                FixedPoint.parse("0.02"), FixedPoint.parse("0.03"), 32, 16, 2L);
        int differs = 0;
        for (int i = 0; i < 500; i++) {
            Coord coord = Coord.of(i % 512, (i * 13) % 512);
            if (!WorldGenerator.generate(a, catalog, coord).equals(WorldGenerator.generate(b, catalog, coord))) {
                differs++;
            }
        }
        assertThat(differs).as("换种子后应当有相当比例的格子不同（B14 赛季重置就靠换种子）")
                .isGreaterThan(10);
    }

    @Test
    @DisplayName("实体密度落在配置范围内（±30% 容差），且野怪与资源点不会抢同一格")
    void densitiesMatchConfig() {
        WorldGenerator.Rules rules = rules();
        WorldGenerator.Catalog catalog = catalog();
        int monsters = 0;
        int resources = 0;
        int total = 0;
        for (int y = 0; y < 512; y += 2) {
            for (int x = 0; x < 512; x += 2) {
                WorldGenerator.Cell cell = WorldGenerator.generate(rules, catalog, Coord.of(x, y));
                total++;
                if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
                    monsters++;
                } else if (cell.entityType() == WorldGenerator.EntityType.RESOURCE) {
                    resources++;
                }
                // 一格只能是空/怪/资源三者之一，这是 Cell 的类型本身保证的
                assertThat(cell.entityType()).isNotNull();
            }
        }
        double monsterRate = (double) monsters / total;
        double resourceRate = (double) resources / total;
        assertThat(monsterRate).as("野怪密度配置为 2%").isBetween(0.014d, 0.026d);
        assertThat(resourceRate).as("资源点密度配置为 3%").isBetween(0.021d, 0.039d);
        assertThat(monsters + resources).as("两者之和不得超过 5%（配置里是 2%+3%）")
                .isLessThan((int) (total * 0.056d));
    }

    @Test
    @DisplayName("野怪等级按环递增：中心是新手区，越往外越难（B07 §1 的地形基础）")
    void monsterLevelRisesWithDistanceFromCenter() {
        WorldGenerator.Rules rules = rules();
        assertThat(rules.levelAt(Coord.of(256, 256))).as("中心是 1 级").isEqualTo(1);
        assertThat(rules.levelAt(Coord.of(256 + 31, 256))).as("31 格仍在第 1 环").isEqualTo(1);
        assertThat(rules.levelAt(Coord.of(256 + 32, 256))).as("32 格进入第 2 环").isEqualTo(2);
        assertThat(rules.levelAt(Coord.of(0, 0))).as("角落是最高环").isEqualTo(1 + 256 / 32);
        // 生成的野怪等级必须与环数一致，且 id 取自该等级
        Coord far = Coord.of(256 + 64, 256);
        WorldGenerator.Cell cell = WorldGenerator.generate(rules, catalog(), far);
        if (cell.entityType() == WorldGenerator.EntityType.MONSTER) {
            assertThat(cell.level()).isEqualTo(rules.levelAt(far));
            assertThat(cell.entityId()).isEqualTo(catalog().monsterAt(cell.level()));
        }
    }

    @Test
    @DisplayName("出生坐标确定、落在边距内、且不同玩家被铺开而不是挤成一行")
    void spawnCoordsAreDeterministicAndSpread() {
        WorldGenerator.Rules rules = rules();
        assertThat(WorldGenerator.spawnCoord(rules, 0)).isEqualTo(WorldGenerator.spawnCoord(rules, 0));
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        for (long i = 0; i < 500; i++) {
            Coord coord = WorldGenerator.spawnCoord(rules, i);
            assertThat(coord.x()).isBetween(rules.spawnMargin(), rules.worldSize() - rules.spawnMargin() - 1);
            assertThat(coord.y()).isBetween(rules.spawnMargin(), rules.worldSize() - rules.spawnMargin() - 1);
            distinct.add(coord.toString());
        }
        // 黄金角螺旋应当把 500 个玩家铺得很开；直接取模排成一行会让大多数人周围是空的
        assertThat(distinct).as("500 个出生点里重复的不该超过极少数").hasSizeGreaterThan(450);
    }

    // ---------- 迷雾 ----------

    @Test
    @DisplayName("行军经过解锁路径上的所有 chunk，斜向路径不会从两块之间的缝隙穿过去")
    void fogExploresTheWholePath() {
        FogOfWar fog = new FogOfWar();
        fog.explorePath(Coord.of(0, 0), Coord.of(200, 200), 32);
        // 对角线路径经过的块：(0,0) (1,1) (2,2) ... (6,6)
        for (int i = 0; i <= 6; i++) {
            assertThat(fog.isExplored(Coord.of(i * 32 + 1, i * 32 + 1), 32))
                    .as("对角线路径上的第 %d 块必须被解锁", i).isTrue();
        }
        assertThat(fog.isExplored(Coord.of(300, 0), 32)).as("路径外的块不该被解锁").isFalse();
    }

    @Test
    @DisplayName("探索是不可逆的：解锁过的块不会因为离开视野而重新变黑")
    void fogNeverReFogs() {
        FogOfWar fog = new FogOfWar();
        fog.explore(Coord.of(40, 40), 32);
        assertThat(fog.exploredCount()).isEqualTo(1);
        fog.explore(Coord.of(40, 40), 32);
        assertThat(fog.exploredCount()).as("重复解锁是幂等的").isEqualTo(1);
        // restore 传入自己的视图不能把数据清空（ArmyState 上踩过的同一个别名坑）
        fog.restore(fog.chunks());
        assertThat(fog.exploredCount()).as("restore 传入自身视图后必须原样保留").isEqualTo(1);
    }

    // ---------- 行军数值 ----------

    @Test
    @DisplayName("队伍速度取最慢兵种：带了攻城器就按攻城器的速度走")
    void teamSpeedIsTheSlowestUnit() {
        Map<String, Integer> speeds = new LinkedHashMap<>();
        speeds.put("unit_infantry_t1", 4);
        speeds.put("unit_cavalry_t1", 10);
        speeds.put("unit_archer_t1", 6);
        speeds.put("unit_siege_t1", 2);

        Map<String, Long> mixed = new LinkedHashMap<>();
        mixed.put("unit_cavalry_t1", 999L);
        mixed.put("unit_siege_t1", 1L);
        assertThat(MarchCalculator.teamSpeed(mixed, speeds))
                .as("999 个轻骑兵 + 1 个攻城器 ⇒ 速度 2。取平均或取最快都会抹掉攻城器「慢」这个身份")
                .isEqualTo(2);

        Map<String, Long> cavalryOnly = new LinkedHashMap<>();
        cavalryOnly.put("unit_cavalry_t1", 100L);
        cavalryOnly.put("unit_siege_t1", 0L);   // 数量为 0 的兵种不算随行
        assertThat(MarchCalculator.teamSpeed(cavalryOnly, speeds)).isEqualTo(10);

        Map<String, Integer> incomplete = new LinkedHashMap<>();
        incomplete.put("unit_infantry_t1", 4);
        assertThatThrownBy(() -> MarchCalculator.teamSpeed(mixed, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .as("漏配速度不能当成「无限快」，那会让这个兵种变成瞬移外挂")
                .hasMessageContaining("缺少兵种");
    }

    @Test
    @DisplayName("负载上限 = Σ(兵数 × 该兵 load)")
    void loadCapIsSumOfUnitLoads() {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("unit_infantry_t1", 100L);
        units.put("unit_siege_t1", 10L);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("unit_infantry_t1", 20L);
        loads.put("unit_siege_t1", 40L);
        assertThat(MarchCalculator.loadCap(units, loads)).isEqualTo(100 * 20 + 10 * 40);
    }

    @Test
    @DisplayName("B07 开放问题 2 的节奏：步兵横穿全图约 40 分钟，攻城器约 80 分钟，轻骑兵约 16 分钟")
    void marchDurationMatchesDesignedPace() {
        long perTile = FixedPoint.parse("9.4");
        int fullMap = 1022;   // 511 + 511，全图最大曼哈顿距离
        assertThat(MarchCalculator.durationSeconds(fullMap, 4, perTile, 0L))
                .as("重步兵（speed 4）横穿全图应约 40 分钟").isBetween(2350L, 2450L);
        assertThat(MarchCalculator.durationSeconds(fullMap, 10, perTile, 0L))
                .as("轻骑兵（speed 10）应约 16 分钟").isBetween(930L, 990L);
        assertThat(MarchCalculator.durationSeconds(fullMap, 2, perTile, 0L))
                .as("攻城器（speed 2）应约 80 分钟 —— 慢到必须提前规划，这是它的兵种身份")
                .isBetween(4700L, 4900L);
    }

    @Test
    @DisplayName("加速按 (1 + 加成) 缩短时长；原地行军给 1 秒而不是 0 秒")
    void speedBonusShortensDuration() {
        long perTile = FixedPoint.parse("9.4");
        long base = MarchCalculator.durationSeconds(100, 4, perTile, 0L);
        long withBonus = MarchCalculator.durationSeconds(100, 4, perTile, FixedPoint.parse("0.50"));
        assertThat(withBonus).as("+50% 速度 ⇒ 时长应为原来的 2/3")
                .isBetween(base * 2 / 3 - 2, base * 2 / 3 + 2);
        assertThat(MarchCalculator.durationSeconds(0, 4, perTile, 0L))
                .as("0 秒会让「到达」与「出发」同一时刻，延迟队列无法排序").isEqualTo(1L);
        assertThatThrownBy(() -> MarchCalculator.durationSeconds(10, 4, perTile, -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("加速不得为负");
        assertThatThrownBy(() -> MarchCalculator.durationSeconds(10, 0, perTile, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("速度必须 >= 1");
    }

    @Test
    @DisplayName("采集：采满负载固定需要 GATHER_FILL_SECONDS，与负载大小无关")
    void gatheringFillsInFixedTimeRegardlessOfCapacity() {
        long fillSeconds = 900L;
        assertThat(MarchCalculator.gathered(2000L, 450_000L, fillSeconds)).as("一半时间 ⇒ 一半负载")
                .isEqualTo(1000L);
        assertThat(MarchCalculator.gathered(2000L, 900_000L, fillSeconds)).as("采满").isEqualTo(2000L);
        assertThat(MarchCalculator.gathered(2000L, 9_000_000L, fillSeconds)).as("超时不会溢出负载")
                .isEqualTo(2000L);
        // 负载大 10 倍，采满时间不变 —— 这正是「按采满时间定」的意义：
        // 若按速率定，负载大的队伍要采 10 倍时间，玩家会把所有兵换成攻城器当运输队
        assertThat(MarchCalculator.gathered(20000L, 450_000L, fillSeconds)).isEqualTo(10000L);
        assertThat(MarchCalculator.gathered(20000L, 900_000L, fillSeconds)).isEqualTo(20000L);
        assertThat(MarchCalculator.gathered(0L, 900_000L, fillSeconds)).isZero();
        assertThatThrownBy(() -> MarchCalculator.gathered(100L, 100L, 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("采满时长必须为正");
    }

    // ---------- 行军状态机与位置插值 ----------

    private static March march(Map<String, Long> units, long durationSec) {
        return new March("m1", "p1", Coord.of(10, 10), Coord.of(110, 10), NOW,
                NOW + durationSec * 1000L, units, List.of("hero_ssr_01"),
                2000L, 4, March.TargetType.RESOURCE, "WOOD", March.Action.GATHER);
    }

    private static Map<String, Long> infantry(long count) {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("unit_infantry_t1", count);
        return units;
    }

    @Test
    @DisplayName("验收7：去程中召回 ⇒ 返程时长 = 已行军时长，兵力零损失")
    void recallMirrorsTraveledTimeWithZeroLoss() {
        March m = march(infantry(100L), 1000L);
        long recallAt = NOW + 400_000L;      // 已走 400 秒（全程 1000 秒）
        long returnAt = m.recall(recallAt);

        assertThat(returnAt - recallAt).as("返程 = 已走的 400 秒").isEqualTo(400_000L);
        assertThat(m.status()).isEqualTo(March.Status.RETURNING);
        assertThat(m.totalUnits()).as("召回不损失兵力").isEqualTo(100L);
        assertThat(m.returnStartAt()).isEqualTo(recallAt);
        assertThatThrownBy(() -> m.recall(recallAt))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已在返程中");
    }

    @Test
    @DisplayName("验收7：已到达（采集中）后召回 ⇒ 返程 = 完整去程时长，采到的负载带回去")
    void recallAfterArrivalTakesFullTrip() {
        March m = march(infantry(100L), 1000L);
        m.arrive(NOW + 1000_000L);
        assertThat(m.status()).isEqualTo(March.Status.GATHERING);
        assertThat(m.addLoad(1500L)).isEqualTo(1500L);
        assertThat(m.addLoad(900L)).as("负载上限 2000，超出部分装不进去").isEqualTo(500L);
        assertThat(m.load()).isEqualTo(2000L);

        long recallAt = NOW + 1500_000L;
        long returnAt = m.recall(recallAt);
        assertThat(returnAt - recallAt).as("已到达 ⇒ 返程是完整的 1000 秒").isEqualTo(1000_000L);
        assertThat(m.load()).as("召回不损失已采集的负载").isEqualTo(2000L);
        assertThat(m.totalUnits()).isEqualTo(100L);
    }

    @Test
    @DisplayName("交战中不能召回：能召回就等于「打不过就跑，一个兵都不损失」，战斗会失去风险")
    void fightingMarchCannotBeRecalled() {
        March m = new March("m2", "p1", Coord.of(0, 0), Coord.of(10, 0), NOW, NOW + 100_000L,
                infantry(10L), List.of(), 200L, 4,
                March.TargetType.MONSTER, "mapmonster_lv01", March.Action.ATTACK);
        m.arrive(NOW + 100_000L);
        assertThat(m.status()).isEqualTo(March.Status.FIGHTING);
        assertThatThrownBy(() -> m.recall(NOW + 200_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("交战中不能召回");
    }

    @Test
    @DisplayName("验收1/10：位置插值连续 —— 召回瞬间不跳变，到点不越界，全程单调")
    void positionInterpolationIsContinuous() {
        March m = march(infantry(100L), 1000L);
        Coord start = m.from();
        Coord end = m.to();

        assertThat(m.positionAt(NOW)).isEqualTo(start);
        assertThat(m.positionAt(NOW + 500_000L)).as("一半时间 ⇒ 一半路程")
                .isEqualTo(Coord.of(60, 10));
        assertThat(m.positionAt(NOW + 1000_000L)).isEqualTo(end);
        assertThat(m.positionAt(NOW + 9999_000L)).as("超过到达时刻也不越界").isEqualTo(end);

        // 去程单调不减
        int previousX = start.x();
        for (long t = NOW; t <= NOW + 1000_000L; t += 10_000L) {
            int x = m.positionAt(t).x();
            assertThat(x).as("去程位置必须单调不减，t=%d", t).isGreaterThanOrEqualTo(previousX);
            previousX = x;
        }

        // 召回后位置必须从当前所在格开始往回走，而不是瞬间跳到出发点或终点
        long recallAt = NOW + 500_000L;
        Coord atRecall = m.positionAt(recallAt);
        m.recall(recallAt);
        assertThat(m.positionAt(recallAt)).as("召回瞬间位置不能跳变").isEqualTo(atRecall);
        assertThat(m.positionAt(recallAt + 250_000L).x())
                .as("返程走一半 ⇒ 位置在回家路上").isBetween(start.x(), atRecall.x());
        assertThat(m.positionAt(recallAt + 500_000L)).as("返程结束应到家").isEqualTo(start);
        assertThat(m.progressFixed(recallAt + 500_000L)).isEqualTo(FixedPoint.SCALE);
    }

    @Test
    @DisplayName("到点前不允许 arrive：延迟队列提前触发是队列实现的 bug，必须暴露而不是容忍")
    void arriveBeforeDueIsRejected() {
        March m = march(infantry(10L), 1000L);
        assertThatThrownBy(() -> m.arrive(NOW + 999_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("还没到点");
        m.arrive(NOW + 1000_000L);
        assertThatThrownBy(() -> m.arrive(NOW + 1000_000L))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("只有去程中");
    }

    @Test
    @DisplayName("扣兵按枚举顺序、扣到 0 为止，绝不为负（负兵力会让被打的一方在战斗里变强）")
    void lossesAreAppliedInEnumOrderAndNeverNegative() {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("unit_infantry_t1", 50L);
        units.put("unit_archer_t1", 30L);
        March m = new March("m3", "p1", Coord.of(0, 0), Coord.of(5, 0), NOW, NOW + 100_000L,
                units, List.of(), 500L, 4,
                March.TargetType.MONSTER, "mapmonster_lv01", March.Action.ATTACK);

        Map<String, Long> losses = new LinkedHashMap<>();
        losses.put("unit_infantry_t1", 80L);   // 超过持有量
        losses.put("unit_archer_t1", 10L);
        Map<String, Long> actual = m.applyLosses(losses);
        assertThat(actual.get("unit_infantry_t1")).as("最多扣掉现有的 50").isEqualTo(50L);
        assertThat(actual.get("unit_archer_t1")).isEqualTo(10L);
        assertThat(m.units().get("unit_infantry_t1")).as("扣到 0 的兵种应被移除").isNull();
        assertThat(m.units().get("unit_archer_t1")).isEqualTo(20L);
        assertThat(m.totalUnits()).isEqualTo(20L);
        // 迭代顺序必须是枚举声明顺序（INFANTRY 在 ARCHER 前），HashMap 会随机
        assertThat(new java.util.ArrayList<>(m.units().keySet()))
                .containsExactly("unit_archer_t1");
    }

    @Test
    @DisplayName("构造校验：不派兵、到达早于出发、速度为 0、数量为负都拒绝")
    void marchRejectsInvalidConstruction() {
        assertThatThrownBy(() -> new March("m", "p", Coord.of(0, 0), Coord.of(1, 1), NOW, NOW + 1000L,
                Map.of(), List.of(), 100L, 4, March.TargetType.EMPTY, null, March.Action.STATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须带兵");
        assertThatThrownBy(() -> new March("m", "p", Coord.of(0, 0), Coord.of(1, 1), NOW, NOW - 1L,
                infantry(1L), List.of(), 100L, 4, March.TargetType.EMPTY, null, March.Action.STATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("到达时刻不得早于出发");
        assertThatThrownBy(() -> new March("m", "p", Coord.of(0, 0), Coord.of(1, 1), NOW, NOW + 1000L,
                infantry(1L), List.of(), 100L, 0, March.TargetType.EMPTY, null, March.Action.STATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("速度必须 >= 1");
        Map<String, Long> negative = new LinkedHashMap<>();
        negative.put("unit_infantry_t1", -1L);
        assertThatThrownBy(() -> new March("m", "p", Coord.of(0, 0), Coord.of(1, 1), NOW, NOW + 1000L,
                negative, List.of(), 100L, 4, March.TargetType.EMPTY, null, March.Action.STATION))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
    }

    @Test
    @DisplayName("restore 传入自己的视图不会清空兵种（ArmyState 上踩过的同一个别名坑）")
    void marchRestoreIsSafeWithOwnView() {
        March m = march(infantry(100L), 1000L);
        m.restore(m.arriveAt(), m.returnArriveAt(), m.returnStartAt(), m.returnFrom(), 500L,
                March.Status.GATHERING, NOW, m.units());
        assertThat(m.totalUnits()).isEqualTo(100L);
        assertThat(m.load()).isEqualTo(500L);
        assertThat(m.status()).isEqualTo(March.Status.GATHERING);
    }

    // ---------- 侦查误差（验收 8） ----------

    @Test
    @DisplayName("验收8：误差幅度随等级差单调放大，且严格落在配置的 [5%, 40%] 区间内")
    void scoutErrorGrowsWithLevelDiffAndStaysInRange() {
        long base = FixedPoint.parse("0.05");
        long perLevel = FixedPoint.parse("0.0175");
        long max = FixedPoint.parse("0.40");

        assertThat(ScoutReport.errorFixed(0, base, perLevel, max))
                .as("同级 ⇒ ±5%").isEqualTo(base);
        assertThat(ScoutReport.errorFixed(20, base, perLevel, max))
                .as("差 20 级 ⇒ 5% + 20×1.75% = 40%，正好是上限").isEqualTo(max);
        assertThat(ScoutReport.errorFixed(50, base, perLevel, max))
                .as("超过 20 级后封顶：再往上情报就变成纯噪声，玩家会直接放弃侦查系统")
                .isEqualTo(max);

        long previous = -1L;
        for (int diff = 0; diff <= 25; diff++) {
            long error = ScoutReport.errorFixed(diff, base, perLevel, max);
            assertThat(error).as("等级差 %d 的误差必须单调不减", diff).isGreaterThanOrEqualTo(previous);
            assertThat(error).isBetween(base, max);
            previous = error;
        }
        assertThatThrownBy(() -> ScoutReport.errorFixed(-1, base, perLevel, max))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> ScoutReport.errorFixed(0, FixedPoint.parse("0.50"), perLevel, max))
                .isInstanceOf(IllegalArgumentException.class)
                .as("基础误差大于封顶 ⇒ 封顶形同虚设").hasMessageContaining("不得大于封顶");
    }

    @Test
    @DisplayName("验收8：1000 次采样，观测值全部落在 ±误差幅度内，且误差真的在区间里铺开（不是恒取端点）")
    void distortedValuesStayWithinConfiguredBand() {
        long errorFixed = FixedPoint.parse("0.20");   // ±20%
        long truth = 10_000L;
        Map<String, Long> truthMap = new LinkedHashMap<>();
        truthMap.put("power", truth);

        int below = 0;
        int above = 0;
        for (int i = 0; i < 1000; i++) {
            long observed = ScoutReport.distort(truthMap, errorFixed, 1000L + i).get("power");
            long lower = truth - FixedPoint.round(FixedPoint.mul(FixedPoint.of(truth), errorFixed));
            long upper = truth + FixedPoint.round(FixedPoint.mul(FixedPoint.of(truth), errorFixed));
            assertThat(observed)
                    .as("第 %d 次采样：观测值必须严格落在 ±20%% 的区间内（验收 8）", i)
                    .isBetween(lower, upper);
            if (observed < truth) {
                below++;
            } else if (observed > truth) {
                above++;
            }
        }
        // 误差必须是双向的：只偏高或只偏低都说明随机数被截断了，
        // 而那会让玩家学会「情报总是低估敌人」，误差机制就退化成一个固定偏移
        assertThat(below).as("1000 次里应当有相当比例偏低").isGreaterThan(300);
        assertThat(above).as("1000 次里应当有相当比例偏高").isGreaterThan(300);
    }

    @Test
    @DisplayName("同 seed 的误差完全一致（玩家反复打开同一份报告不能看到数字在变），换 seed 就变")
    void distortionIsSeededAndReproducible() {
        Map<String, Long> truth = new LinkedHashMap<>();
        truth.put("power", 12345L);
        truth.put("totalUnits", 678L);
        long error = FixedPoint.parse("0.30");

        assertThat(ScoutReport.distort(truth, error, 42L))
                .isEqualTo(ScoutReport.distort(truth, error, 42L));
        assertThat(ScoutReport.distort(truth, error, 42L))
                .isNotEqualTo(ScoutReport.distort(truth, error, 43L));
        // 多指标各自独立：加一个指标不会改变已有指标的误差
        Map<String, Long> extended = new LinkedHashMap<>(truth);
        extended.put("gold", 100L);
        Map<String, Long> withExtra = ScoutReport.distort(extended, error, 42L);
        assertThat(withExtra.get("power")).isEqualTo(ScoutReport.distort(truth, error, 42L).get("power"));
        assertThat(withExtra.get("totalUnits"))
                .isEqualTo(ScoutReport.distort(truth, error, 42L).get("totalUnits"));
    }

    @Test
    @DisplayName("观测值永不为负、也永不因为误差变成 0（把一支真实存在的军队报成「0 兵」是致命误导）")
    void distortedValuesAreNeverZeroOrNegative() {
        Map<String, Long> truth = new LinkedHashMap<>();
        truth.put("power", 1L);       // 极小的真实值，误差最容易把它压到 0 以下
        truth.put("totalUnits", 3L);
        for (long seed = 0; seed < 500; seed++) {
            Map<String, Long> observed = ScoutReport.distort(truth, FixedPoint.parse("0.40"), seed);
            assertThat(observed.get("power")).isGreaterThanOrEqualTo(1L);
            assertThat(observed.get("totalUnits")).isGreaterThanOrEqualTo(1L);
        }
        // 真实值为 0 时观测值也必须是 0：给「确实没有」的东西编一个非零观测值，
        // 玩家会去防一个不存在的威胁
        Map<String, Long> zeroTruth = new LinkedHashMap<>();
        zeroTruth.put("gold", 0L);
        assertThat(ScoutReport.distort(zeroTruth, FixedPoint.parse("0.40"), 7L).get("gold")).isZero();
    }

    @Test
    @DisplayName("报告过期判定：expiresAt 之前有效、之后 expired，剩余时间永不为负")
    void reportExpiryIsStrict() {
        Map<String, Long> observed = new LinkedHashMap<>();
        observed.put("power", 100L);
        ScoutReport.Report report = new ScoutReport.Report("r1", "p1", Coord.of(5, 5), "target",
                3, NOW, NOW + 1800_000L, observed, FixedPoint.parse("0.05"), 1L);

        assertThat(report.expired(NOW)).isFalse();
        assertThat(report.remainingMs(NOW)).isEqualTo(1800_000L);
        assertThat(report.expired(NOW + 1799_999L)).isFalse();
        assertThat(report.expired(NOW + 1800_000L)).as("到期即失效").isTrue();
        assertThat(report.remainingMs(NOW + 9999_000L)).as("过期后剩余时间必须是 0 而不是负数").isZero();

        assertThatThrownBy(() -> new ScoutReport.Report("r2", "p1", Coord.of(5, 5), null,
                3, NOW, NOW, observed, 500L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .as("有效期为 0 的情报等于没有情报").hasMessageContaining("过期时刻必须晚于");
    }

    // ---------- 环带落点（B11 §四：新 Bot 孵化在真人附近 5~15 格） ----------

    @Test
    @DisplayName("环带落点：每条候选都在 [min,max] 的曼哈顿环带内，且同一个种子可复现")
    void ringAroundStaysInBandAndIsReproducible() {
        Coord center = Coord.of(200, 200);
        com.ironoath.common.rng.Rng rng = com.ironoath.common.rng.Rng.of(7L);
        com.ironoath.common.rng.Rng replay = com.ironoath.common.rng.Rng.of(7L);
        for (int i = 0; i < 200; i++) {
            Coord coord = WorldGenerator.ringAround(center, 5, 15, rng).orElseThrow();
            assertThat(coord.distanceTo(center))
                    .as("第 %d 个候选距中心 %d 格，必须落在 [5,15]", i, coord.distanceTo(center))
                    .isBetween(5, 15);
            assertThat(coord.x()).as("坐标不得为负（世界从 (0,0) 起）").isNotNegative();
            assertThat(coord.y()).isNotNegative();
            assertThat(WorldGenerator.ringAround(center, 5, 15, replay)).as("同种子同序列").contains(coord);
        }
        assertThatThrownBy(() -> WorldGenerator.ringAround(center, 0, 15, com.ironoath.common.rng.Rng.of(1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("环带区间非法");
    }

    @Test
    @DisplayName("环带落点：地图角上仍取到合法候选（那一支在第一象限），绝不会塞一个越界坐标")
    void ringAroundStaysValidAtTheWorldCorner() {
        // 角上不是「取不到」，而是「只有第一象限那一支合法」——
        // 所以这里断的是「负坐标被拒绝、候选仍然落在环带内」，而不是「返回空」。
        // 空分支只在重抽耗尽的极小概率下出现（角上接受率约 12%，64 次全落空的概率约 3e-4），
        // 由调用方退回确定性螺旋兜底
        Coord corner = Coord.of(0, 0);
        com.ironoath.common.rng.Rng rng = com.ironoath.common.rng.Rng.of(3L);
        int produced = 0;
        for (int i = 0; i < 200; i++) {
            Optional<Coord> candidate = WorldGenerator.ringAround(corner, 5, 15, rng);
            if (candidate.isEmpty()) {
                continue;
            }
            produced++;
            Coord coord = candidate.get();
            assertThat(coord.distanceTo(corner)).as("角上的候选也要落在 [5,15]").isBetween(5, 15);
            assertThat(coord.x()).isNotNegative();
            assertThat(coord.y()).isNotNegative();
        }
        assertThat(produced).as("角上 200 次抽样全都取不到候选，等于把出生区一角封死了").isPositive();
    }
}
