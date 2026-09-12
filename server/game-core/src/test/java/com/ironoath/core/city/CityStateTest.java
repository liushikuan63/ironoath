package com.ironoath.core.city;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.num.FixedPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：城建系统单测 —— 覆盖 B03 验收 1、4、5、6、7、8、11（可 JUnit 化的部分）。
 * 依赖：JUnit 5 + AssertJ，无 Spring、无容器、无定时器（时间由测试直接推进）。
 *
 * <p>验收 2（并发）、10（requestId 幂等）依赖存储层的分布式锁，属于应用层职责，
 * 在 game-web 的集成测试里覆盖；本类验证的是聚合自身的并发防线
 * （{@link CityState#startUpgrade} 会二次校验队列，不完全信任 validateUpgrade 的结论）。
 */
class CityStateTest {

    private static final long HOUR = 3_600_000L;

    /** 规则参数逐项取自 contract/config/city_rule.json。 */
    private static CityRules rules() {
        return new CityRules(
                6,                                  // city_rule_grid_size
                true,                               // city_rule_wall_edge_only
                true,                               // city_rule_center_is_main_city
                1,                                  // city_rule_base_queue_count
                3,                                  // city_rule_max_queue_count
                2,                                  // city_rule_newbie_free_queue_count
                FixedPoint.parse("0.60"),           // city_rule_cancel_refund_ratio
                FixedPoint.parse("0.01"),           // city_rule_help_per_person_ratio
                FixedPoint.parse("0.20"),           // city_rule_help_cap_ratio
                5L,                                 // city_rule_ad_speedup_daily_limit
                300L,                               // city_rule_ad_speedup_seconds
                3600L);                             // city_rule_move_cooldown_seconds
    }

    private static CityState cityWith(String... configIds) {
        CityState city = new CityState();
        CityRules rules = rules();
        // 中心格 (3,3) 固定为主城，其余建筑从 (0,0) 起沿内部格排布
        int index = 0;
        for (String configId : configIds) {
            int x;
            int y;
            if ("main_city".equals(configId)) {
                x = 3;
                y = 3;
            } else {
                x = 1 + index % 4;
                y = 1 + index / 4;
                index++;
            }
            city.place("inst_" + configId, configId, x, y, rules,
                    "wall".equals(configId), "main_city".equals(configId));
        }
        return city;
    }

    private static Map<String, Long> resources(long wood, long stone) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("WOOD", wood);
        m.put("STONE", stone);
        return m;
    }

    // ---------- 验收 1 / 11：惰性结算 ----------

    @Test
    @DisplayName("验收1：离线 10 分钟按真实时长结算，且不超过仓储上限")
    void offlineSettlementUsesRealElapsedTime() {
        long now = 1_700_000_000_000L;
        // 每小时 1200 ⇒ 每分钟 20 ⇒ 10 分钟 200
        ResourceSettlement.Result r = ResourceSettlement.settle(
                1000L, 100_000L, 1200L, now - 10 * 60_000L, now);

        assertThat(r.current()).isEqualTo(1200L);
        assertThat(r.produced()).isEqualTo(200L);
        assertThat(r.overflow()).isZero();
        assertThat(r.isFull()).isFalse();
        assertThat(r.lastSettle()).isEqualTo(now);
    }

    @Test
    @DisplayName("持有量已超上限时不抛异常也不清仓：停产、推进基准、并把异常状态标出来")
    void holdingsOverCapStopProductionWithoutThrowingOrDestroyingStock() {
        long now = 1_700_000_000_000L;
        // 容量被调小之后的典型形态：存档里有 5000，而现在的上限是 1000
        ResourceSettlement.Result r = ResourceSettlement.settle(
                5_000L, 1_000L, 1_200L, now - HOUR, now);

        assertThat(r.current())
                .as("绝不把持有量夹回上限：销毁玩家的存货比超容量本身严重得多").isEqualTo(5_000L);
        assertThat(r.produced()).as("超上限期间停产").isZero();
        assertThat(r.lastSettle()).as("基准仍要推进，否则腾空仓库时会一次性拿到停产期间的全部产量").isEqualTo(now);
        assertThat(r.isFull()).as("UI 该看到「已满」").isTrue();
        assertThat(r.heldOverCap())
                .as("不抛异常之后，这个状态只能靠标志位冒泡给调用方 WARN，否则就永久静默").isTrue();
    }

    @Test
    @DisplayName("验收11：产出达到上限即停产，超出部分永久损失且不结转")
    void productionStopsAtCap() {
        long now = 1_700_000_000_000L;
        // 容量 2000、已有 1900、每小时 1200：100 单位空间只需 5 分钟填满
        ResourceSettlement.Result r = ResourceSettlement.settle(
                1900L, 2000L, 1200L, now - 60 * 60_000L, now);

        assertThat(r.current()).as("不得超过容量").isEqualTo(2000L);
        assertThat(r.produced()).as("只入账填满容量所需的 100").isEqualTo(100L);
        assertThat(r.overflow()).as("超出的 1100 永久损失").isEqualTo(1100L);
        assertThat(r.isFull()).isTrue();
        // 离线 1 小时，但容量只够 5 分钟填满 ⇒ 满仓时刻 = 结算起点 + 5 分钟 = now - 55 分钟
        assertThat(r.cappedAt()).as("满仓时刻应为结算起点后约 5 分钟")
                .isBetween(now - 56 * 60_000L, now - 54 * 60_000L);
        // lastSettle 仍推进到 now：否则玩家腾空仓库的瞬间会一次性拿到满仓期间的全部产量
        assertThat(r.lastSettle()).isEqualTo(now);
    }

    @Test
    @DisplayName("已满仓时继续挂机：产量为 0，不会在腾空后补发")
    void fullWarehouseProducesNothing() {
        long now = 1_700_000_000_000L;
        ResourceSettlement.Result r = ResourceSettlement.settle(2000L, 2000L, 1200L, now - 10 * HOUR, now);
        assertThat(r.current()).isEqualTo(2000L);
        assertThat(r.produced()).isZero();
        assertThat(r.isFull()).isTrue();
        assertThat(r.overflow()).as("理论产量 12000 全部浪费").isEqualTo(12000L);
    }

    @Test
    @DisplayName("不足 1 单位的零头向下取整：反复读取不能刷出资源")
    void fractionalOutputIsTruncatedNotRounded() {
        long now = 1_700_000_000_000L;
        // 每小时 1 单位，读取 1000 次每次间隔 1 秒 ⇒ 每次理论产量 0.000278，四舍五入会刷出 1000 单位
        long accumulated = 0L;
        long settleFrom = now;
        for (int i = 0; i < 1000; i++) {
            long t = now + (i + 1) * 1000L;
            ResourceSettlement.Result r = ResourceSettlement.settle(0L, 1_000_000L, 1L, settleFrom, t);
            accumulated += r.produced();
            settleFrom = r.lastSettle();
        }
        assertThat(accumulated).as("1000 秒 × 每小时 1 单位 ⇒ 总产量应为 0（向下取整）").isZero();
    }

    @Test
    @DisplayName("分多次结算的总产量等于一次结算：零头结转，既不刷出来也不被吃掉")
    void manySmallSettlesProduceTheSameTotalAsOneBigSettle() {
        long now = 1_700_000_000_000L;
        long perHour = 1200L;
        long cap = 1_000_000L;
        long window = 3 * HOUR + 7 * 60_000L;   // 刻意取一个不整的小时数

        long oneShot = ResourceSettlement.settle(0L, cap, perHour, now, now + window).produced();

        // 客户端每 30 秒拉一次面板：这是最常见的结算频率，也是零头损失最严重的场景
        long accumulated = 0L;
        long current = 0L;
        long settleFrom = now;
        for (long t = now + 30_000L; t <= now + window; t += 30_000L) {
            ResourceSettlement.Result r = ResourceSettlement.settle(current, cap, perHour, settleFrom, t);
            accumulated += r.produced();
            current = r.current();
            settleFrom = r.lastSettle();
        }
        assertThat(accumulated)
                .as("分 %d 次结算必须与一次结算总量相同；少了说明零头被吃掉，多了说明可以刷",
                        window / 30_000L)
                .isEqualTo(oneShot);
    }

    @Test
    @DisplayName("体力这类小 perHour 资源：上线比恢复周期更频繁也必须能恢复")
    void slowRecoveryStillAccruesWhenPolledFasterThanThePeriod() {
        long now = 1_700_000_000_000L;
        // 每 6 分钟恢复 1 点 = 每小时 10 点。玩家每 5 分钟上线一次，
        // 也就是每一次上线都还没攒够 1 点 —— 若零头不结转，体力会永远停在 0
        long perHour = 10L;
        long current = 0L;
        long settleFrom = now;
        for (int i = 1; i <= 12; i++) {
            long t = now + i * 5 * 60_000L;
            ResourceSettlement.Result r = ResourceSettlement.settle(current, 100L, perHour, settleFrom, t);
            current = r.current();
            settleFrom = r.lastSettle();
        }
        assertThat(current)
                .as("60 分钟 × 每小时 10 点 = 10 点。上线频率高于恢复周期不该让恢复停下来")
                .isEqualTo(10L);
    }

    @Test
    @DisplayName("时钟回拨时不结算也不推进基准：推进会永久吃掉这段时间的产量")
    void clockRollbackDoesNotAdvanceSettlePoint() {
        long now = 1_700_000_000_000L;
        ResourceSettlement.Result r = ResourceSettlement.settle(1000L, 100_000L, 1200L, now, now - HOUR);
        assertThat(r.produced()).isZero();
        assertThat(r.current()).isEqualTo(1000L);
        assertThat(r.lastSettle()).as("基准时刻不得前进").isEqualTo(now);
    }

    @Test
    @DisplayName("离线结算不溢出：极长离线时间走 BigInteger 兜底")
    void veryLongOfflineDoesNotOverflow() {
        long now = 1_700_000_000_000L;
        // 每小时 1e15 × 离线 1e9 毫秒 ⇒ 中间积 1e24 远超 long
        ResourceSettlement.Result r = ResourceSettlement.settle(
                0L, Long.MAX_VALUE / 4, 1_000_000_000_000_000L, now - 1_000_000_000L, now);
        assertThat(r.produced()).isPositive();
    }

    @Test
    @DisplayName("验收5：升级完成后产出速率按配置曲线提升（P0 × n^1.08）")
    void outputRateRisesWithBuildingLevel() {
        // 伐木场 1 级底产 120/小时（building.json），按 BUILDING_OUTPUT 曲线 n^1.08 递增
        long base = FixedPoint.of(120);
        long exponent = FixedPoint.parse("1.08");
        long level1 = Formula.output(base, 1, exponent);
        long level2 = Formula.output(base, 2, exponent);
        long level10 = Formula.output(base, 10, exponent);

        assertThat(level1).isEqualTo(FixedPoint.of(120));
        assertThat(level2).as("2 级应高于 1 级").isGreaterThan(level1);
        assertThat(level10).as("10 级约为 120 × 10^1.08 ≈ 1443").isBetween(FixedPoint.of(1440), FixedPoint.of(1446));

        // 结算层面：同样的离线时长，10 级建筑的入账量应显著高于 1 级
        long now = 1_700_000_000_000L;
        long perHour1 = FixedPoint.round(level1);
        long perHour10 = FixedPoint.round(level10);
        ResourceSettlement.Result r1 = ResourceSettlement.settle(0L, 1_000_000L, perHour1, now - HOUR, now);
        ResourceSettlement.Result r10 = ResourceSettlement.settle(0L, 1_000_000L, perHour10, now - HOUR, now);
        assertThat(r10.produced()).isGreaterThan(r1.produced() * 10);
    }

    /** 产出曲线求值，与 game-core 的 Formula 保持一致口径。 */
    private static final class Formula {
        static long output(long baseFixed, int level, long exponentFixed) {
            return FixedPoint.powerLaw(baseFixed, level, exponentFixed);
        }
    }

    @Test
    @DisplayName("产率阶跃的边界：pendingFinishTimes 只返回已到点且未收割的完成时刻，升序去重")
    void pendingFinishTimesMarksRateStepBoundaries() {
        long t0 = 1_700_000_000_000L;
        CityState city = cityWith("main_city", "lumber_camp", "quarry");
        city.startUpgrade("inst_lumber_camp", 3600L, 3, t0);   // 完成于 t0 + 1h
        city.startUpgrade("inst_quarry", 7200L, 3, t0);         // 完成于 t0 + 2h

        // 还没到点：没有任何边界，整段窗口都按旧等级算，不会提前吃到新等级的产量
        assertThat(city.pendingFinishTimes(t0)).isEmpty();
        assertThat(city.pendingFinishTimes(t0 + HOUR)).containsExactly(t0 + HOUR);
        assertThat(city.pendingFinishTimes(t0 + 3 * HOUR))
                .as("必须升序：先结算到 1h 并收割，再结算到 2h，顺序反了就会用错等级")
                .containsExactly(t0 + HOUR, t0 + 2 * HOUR);

        // 同一时刻完成的两栋建筑只能产生一个边界，否则同一段时间会被结算两次
        CityState sameTime = cityWith("main_city", "lumber_camp", "quarry");
        sameTime.startUpgrade("inst_lumber_camp", 3600L, 3, t0);
        sameTime.startUpgrade("inst_quarry", 3600L, 3, t0);
        assertThat(sameTime.pendingFinishTimes(t0 + 3 * HOUR)).containsExactly(t0 + HOUR);

        // 收割之后边界必须消失，否则每次读档都会重新切段、重新收割
        sameTime.collectFinished(t0 + 3 * HOUR);
        assertThat(sameTime.pendingFinishTimes(t0 + 3 * HOUR)).isEmpty();
    }

    @Test
    @DisplayName("收割记下的完成时刻是真实 finishAt 而不是读档时刻，否则玩家白丢一段产量")
    void harvestRecordsRealFinishTime() {
        long t0 = 1_700_000_000_000L;
        CityState city = cityWith("main_city", "lumber_camp");
        city.startUpgrade("inst_lumber_camp", 3600L, 3, t0);
        long finishAt = t0 + HOUR;

        // 玩家 30 小时后才上线读档
        city.collectFinished(t0 + 31 * HOUR);
        assertThat(city.building("inst_lumber_camp").lastFinishedAt())
                .as("完成时刻必须是 finishAt：从 finishAt 到读档这段时间建筑已经是新等级了")
                .isEqualTo(finishAt);
    }

    // ---------- 验收 6 / 7：结构化错误 ----------

    @Test
    @DisplayName("验收6：主城等级不足时，错误精确到「需要主城 8 级，当前 6 级」")
    void mainLevelErrorIsPrecise() {
        CityState city = cityWith("main_city", "barracks");
        UpgradeCheck check = city.validateUpgrade("inst_barracks", 2, 40,
                8, 6, null, 0, resources(0, 0), resources(99999, 99999),
                rules(), false, 1_700_000_000_000L);

        assertThat(check.passed()).isFalse();
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_MAIN_LEVEL_LOW);
        assertThat(check.need()).isEqualTo("主城 8 级");
        assertThat(check.current()).isEqualTo("主城 6 级");
        assertThat(check.detail()).isEqualTo("需要 主城 8 级，当前 主城 6 级");
        assertThatThrownBy(check::orThrow)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("主城 8 级");
    }

    @Test
    @DisplayName("验收7：队列满时错误提示里带上「可开启第 2 队列」")
    void queueFullErrorSuggestsOpeningAnother() {
        CityState city = cityWith("main_city", "barracks", "stable");
        long now = 1_700_000_000_000L;
        // 基础队列 1 个，先占掉
        city.startUpgrade("inst_barracks", 600L, 1, now);

        UpgradeCheck check = city.validateUpgrade("inst_stable", 2, 40,
                0, 99, null, 0, resources(0, 0), resources(99999, 99999),
                rules(), false, now);

        assertThat(check.passed()).isFalse();
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_QUEUE_FULL);
        assertThat(check.need()).contains("可开启第 2 队列");
        assertThat(check.current()).contains("已用 1 / 1");
    }

    @Test
    @DisplayName("新手保护期内队列数提升到 2，玩家不会被队列卡死")
    void newbieProtectionGrantsExtraQueue() {
        CityRules rules = rules();
        assertThat(rules.availableQueues(false, 0)).isEqualTo(1);
        assertThat(rules.availableQueues(true, 0)).as("B03 禁止项：新手期不得被队列卡死").isEqualTo(2);
        assertThat(rules.availableQueues(true, 2)).as("上限仍是 3").isEqualTo(3);
        assertThat(rules.availableQueues(false, 99)).as("额外队列不得超过上限").isEqualTo(3);
    }

    @Test
    @DisplayName("资源不足时报出具体是哪种资源、缺多少")
    void resourceShortageNamesTheResource() {
        CityState city = cityWith("main_city", "barracks");
        UpgradeCheck check = city.validateUpgrade("inst_barracks", 2, 40,
                0, 99, null, 0, resources(800, 400), resources(500, 999),
                rules(), false, 1_700_000_000_000L);

        assertThat(check.passed()).isFalse();
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_RESOURCE_LOW);
        assertThat(check.need()).isEqualTo("WOOD 800");
        assertThat(check.current()).isEqualTo("WOOD 500");
    }

    @Test
    @DisplayName("校验顺序：硬条件（队列/是否升级中）先于软条件（等级/资源）")
    void hardConditionsAreCheckedBeforeSoftOnes() {
        CityState city = cityWith("main_city", "barracks");
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);

        // 同时违反「已在升级中」「主城等级不足」「资源不足」三条，应报最先能解决的那条
        UpgradeCheck check = city.validateUpgrade("inst_barracks", 2, 40,
                99, 1, null, 0, resources(99999, 0), resources(0, 0),
                rules(), false, now);
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_UPGRADING);
    }

    // ---------- 地块与放置 ----------

    @Test
    @DisplayName("地块校验：越界、中心格建非主城、内部格建城墙、重复占用均被拒绝")
    void gridRulesAreEnforced() {
        CityState city = new CityState();
        CityRules rules = rules();

        city.place("inst_main", "main_city", 3, 3, rules, false, true);
        city.place("inst_barracks", "barracks", 1, 1, rules, false, false);
        // 占用检查要用非中心格：中心格会先命中「固定为主城」规则，测不到占用分支
        assertThatThrownBy(() -> city.place("inst_x", "stable", 1, 1, rules, false, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被占用");
        assertThat(city.isGridFree(1, 1)).isFalse();
        assertThat(city.isGridFree(2, 2)).isTrue();

        assertThatThrownBy(() -> city.validateGrid(6, 0, rules, false, false))
                .isInstanceOf(BizException.class).hasMessageContaining("0~5");
        assertThatThrownBy(() -> city.validateGrid(-1, 2, rules, false, false))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> city.validateGrid(2, 2, rules, true, false))
                .isInstanceOf(BizException.class).hasMessageContaining("边缘格");
        assertThatThrownBy(() -> city.validateGrid(3, 3, rules, false, false))
                .isInstanceOf(BizException.class).hasMessageContaining("固定为主城");
        assertThatThrownBy(() -> city.validateGrid(1, 1, rules, false, true))
                .isInstanceOf(BizException.class).hasMessageContaining("主城必须建在中心格");

        // 边缘格建城墙合法
        city.validateGrid(0, 2, rules, true, false);
    }

    @Test
    @DisplayName("换位有冷却，冷却中拒绝并给出剩余秒数")
    void moveHasCooldown() {
        CityState city = cityWith("main_city", "barracks");
        CityRules rules = rules();
        long now = 1_700_000_000_000L;

        city.moveTo("inst_barracks", 4, 4, rules, false, false, now);
        assertThat(city.building("inst_barracks").gridX()).isEqualTo(4);

        assertThat(city.moveCooldownRemaining("inst_barracks", rules, now + 1000L))
                .as("冷却 3600 秒，刚换位 1 秒后应还剩约 3599 秒").isBetween(3598L, 3600L);
        assertThatThrownBy(() -> city.moveTo("inst_barracks", 5, 5, rules, false, false, now + 1000L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("冷却");
        assertThat(city.moveCooldownRemaining("inst_barracks", rules, now + 3601_000L)).isZero();
    }

    // ---------- 升级 / 加速 / 取消 ----------

    @Test
    @DisplayName("验收2 的聚合防线：队列已满时 startUpgrade 二次校验并拒绝，不完全信任前置校验")
    void startUpgradeRechecksQueue() {
        CityState city = cityWith("main_city", "barracks", "stable");
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);

        assertThatThrownBy(() -> city.startUpgrade("inst_stable", 600L, 1, now))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("建造队列已满");
        assertThatThrownBy(() -> city.startUpgrade("inst_barracks", 600L, 2, now))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已在升级中");
        assertThat(city.usedQueues()).isEqualTo(1);
    }

    @Test
    @DisplayName("验收3：加速到 0 立即完成，剩余时间与进度都不越界")
    void speedUpNeverGoesNegative() {
        CityState city = cityWith("main_city", "barracks");
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);
        BuildingInstance b = city.building("inst_barracks");

        assertThat(b.remainingSeconds(now)).isEqualTo(600L);
        assertThat(b.progressFixed(now)).isZero();

        // 只加速 100 秒
        assertThat(city.speedUp("inst_barracks", 100L, now)).isEqualTo(100L);
        assertThat(b.remainingSeconds(now)).isEqualTo(500L);

        // 剩余 500 秒时加速 6000 秒：只提前 500，多出的不记负数
        assertThat(city.speedUp("inst_barracks", 6000L, now)).isEqualTo(500L);
        assertThat(b.remainingSeconds(now)).as("不得为负").isZero();
        assertThat(b.progressFixed(now)).as("进度不得超过 100%").isEqualTo(FixedPoint.ONE);

        city.collectFinished(now);
        assertThat(b.level()).isEqualTo(1);
        assertThat(b.status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(city.usedQueues()).isZero();
    }

    @Test
    @DisplayName("帮助加速按「原始总时长 × 比例」折算：10 次 = 10%，而城建侧不再自己设上限")
    void helpSpeedUpByRatioAppliesOnOriginalDuration() {
        CityState city = cityWith("main_city", "barracks");
        long now = 1_700_000_000_000L;
        long duration = 10_000L;
        city.startUpgrade("inst_barracks", duration, 1, now);
        BuildingInstance b = city.building("inst_barracks");

        long totalReduced = 0L;
        for (int i = 0; i < 10; i++) {
            totalReduced += city.speedUpByRatio("inst_barracks", FixedPoint.parse("0.01"), now);
        }
        assertThat(b.helpCount()).isEqualTo(10);
        // 每次 1% × 原始总时长 10000 秒 = 100 秒，10 次 = 1000 秒（允许取整误差）。
        // 基数必须是原始总时长：用剩余时长做基数会逐次复利缩水，永远追不上账本给的上限
        assertThat(totalReduced).isBetween(990L, 1010L);
        assertThat(b.remainingSeconds(now)).isBetween(duration - 1010L, duration - 990L);

        // 再帮 41 次：上限**不在这里**判（2026-09-11 裁决：单目标封顶以社交账本的
        // global.HELP_SPEEDUP_TOTAL_CAP 为权威）。所以这一段验的是"城建侧不越权"——
        // 它不会在第 20 次或第 50 次自己停下，停下来的责任在那本社交账上
        for (int i = 0; i < 41; i++) {
            totalReduced += city.speedUpByRatio("inst_barracks", FixedPoint.parse("0.01"), now);
        }
        assertThat(b.helpCount()).as("城建侧不再「到顶就不记账」").isEqualTo(51);
        assertThat(totalReduced).as("51 次 × 1% 全部生效（只受剩余时长截断）")
                .isBetween(5090L, 5110L);
    }

    @Test
    @DisplayName("验收4：取消升级返还 60% 资源，等级不变、队列释放")
    void cancelRefundsSixtyPercent() {
        CityState city = cityWith("main_city", "barracks");
        CityRules rules = rules();
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);

        Map<String, Long> refund = city.cancelUpgrade("inst_barracks", resources(1000, 500), rules);
        assertThat(refund).containsEntry("WOOD", 600L).containsEntry("STONE", 300L);
        assertThat(city.building("inst_barracks").level()).as("取消不得保留等级").isZero();
        assertThat(city.building("inst_barracks").status()).isEqualTo(BuildingStatus.IDLE);
        assertThat(city.usedQueues()).isZero();
    }

    @Test
    @DisplayName("验收4 边界：0 级建筑可取消（返还按投入算），投入为 0 时不产生空返还项")
    void cancelHandlesZeroCost() {
        CityState city = cityWith("main_city", "barracks");
        CityRules rules = rules();
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);

        Map<String, Long> refund = city.cancelUpgrade("inst_barracks", resources(1, 0), rules);
        // 1 × 0.60 = 0.6 ⇒ HALF_UP ⇒ 1；0 × 0.60 = 0 ⇒ 不进返还表（避免下发一堆 0）
        assertThat(refund).containsEntry("WOOD", 1L).doesNotContainKey("STONE");

        assertThatThrownBy(() -> city.cancelUpgrade("inst_barracks", resources(100, 0), rules))
                .as("空闲建筑不能取消").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("满级建筑不能再升级")
    void maxLevelRejectsUpgrade() {
        CityState city = cityWith("main_city", "barracks");
        UpgradeCheck check = city.validateUpgrade("inst_barracks", 41, 40,
                0, 99, null, 0, resources(0, 0), resources(99999, 99999),
                rules(), false, 1_700_000_000_000L);
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_BUILDING_MAX_LEVEL);
    }

    @Test
    @DisplayName("前置建筑等级不足时报出前置建筑与差多少级")
    void preBuildingRequirementIsReported() {
        CityState city = cityWith("main_city", "academy", "barracks");
        UpgradeCheck check = city.validateUpgrade("inst_academy", 2, 40,
                0, 99, "barracks", 5, resources(0, 0), resources(99999, 99999),
                rules(), false, 1_700_000_000_000L);
        assertThat(check.code()).isEqualTo(ErrorCode.CITY_PRE_BUILDING_LOW);
        assertThat(check.need()).isEqualTo("barracks 5 级");
        assertThat(check.current()).isEqualTo("barracks 0 级");
    }

    @Test
    @DisplayName("到点的升级由请求触发收割，服务端不持有任何定时器")
    void collectFinishedIsRequestDriven() {
        CityState city = cityWith("main_city", "barracks", "stable");
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 60L, 2, now);
        city.startUpgrade("inst_stable", 6000L, 2, now);

        // 推进 61 秒：只有兵营到点
        assertThat(city.collectFinished(now + 61_000L)).containsExactly("inst_barracks");
        assertThat(city.building("inst_barracks").level()).isEqualTo(1);
        assertThat(city.building("inst_stable").status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThat(city.usedQueues()).isEqualTo(1);

        // 再推进到马厩也到点
        assertThat(city.collectFinished(now + 6001_000L)).containsExactly("inst_stable");
        assertThat(city.usedQueues()).isZero();
        // 重复收割返回空，不会把等级再 +1
        assertThat(city.collectFinished(now + 6002_000L)).isEmpty();
        assertThat(city.building("inst_stable").level()).isEqualTo(1);
    }

    @Test
    @DisplayName("暂停与恢复：暂停期间剩余时间不推进，恢复后继续")
    void pauseFreezesRemainingTime() {
        CityState city = cityWith("main_city", "barracks");
        long now = 1_700_000_000_000L;
        city.startUpgrade("inst_barracks", 600L, 1, now);
        BuildingInstance b = city.building("inst_barracks");

        city.pause("inst_barracks");
        assertThat(b.status()).isEqualTo(BuildingStatus.PAUSED);
        assertThat(b.remainingSeconds(now + HOUR)).as("暂停期间不显示倒计时").isZero();
        // 暂停中不得被 collectFinished 收割
        assertThat(city.collectFinished(now + HOUR)).isEmpty();

        // 已暂停时再暂停应报错
        assertThatThrownBy(() -> city.pause("inst_barracks")).isInstanceOf(IllegalStateException.class);
        // 未暂停时 resume 应报错
        city.resume("inst_barracks");
        assertThat(b.status()).isEqualTo(BuildingStatus.UPGRADING);
        assertThatThrownBy(() -> city.resume("inst_barracks")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("规则参数校验：新手队列少于基础队列、返还比例越界等都在构造期就被拒绝")
    void rulesRejectInvalidValues() {
        assertThatThrownBy(() -> new CityRules(6, true, true, 2, 3, 1,
                FixedPoint.parse("0.60"), FixedPoint.parse("0.01"), FixedPoint.parse("0.20"),
                5L, 300L, 3600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("新手期不能比平时更难过");
        assertThatThrownBy(() -> new CityRules(2, true, true, 1, 3, 2,
                FixedPoint.parse("0.60"), FixedPoint.parse("0.01"), FixedPoint.parse("0.20"),
                5L, 300L, 3600L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gridSize");
        assertThatThrownBy(() -> new CityRules(6, true, true, 1, 0, 2,
                FixedPoint.parse("0.60"), FixedPoint.parse("0.01"), FixedPoint.parse("0.20"),
                5L, 300L, 3600L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxQueueCount");
        assertThatThrownBy(() -> new CityRules(6, true, true, 1, 3, 2,
                FixedPoint.parse("1.20"), FixedPoint.parse("0.01"), FixedPoint.parse("0.20"),
                5L, 300L, 3600L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cancelRefundFixed");
    }
}
