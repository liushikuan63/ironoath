package com.ironoath.core.army;

import com.ironoath.common.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 职责：军队聚合的规则验证（B05 §二：训练队列、带兵上限、医院、治疗）。
 * 依赖：纯 JUnit，不需要容器、不需要配置表（铁律 2）。
 *
 * <p>把这几条放在纯 Java 层测而不是端到端测，是因为它们都是「做错了不报错」的类型：
 * <ul>
 *   <li>训练中的兵力若不计入带兵上限，玩家可以先塞满队列再换低统率武将绕过它 ——
 *       不需要任何技巧，知道机制就能用，而且服务端日志里一切正常</li>
 *   <li>伤兵超容量若不死，战斗就没有代价，医院这个建筑与它承载的取舍一起作废</li>
 *   <li>剩余时间若能为负，客户端倒计时会显示负数或永远转圈</li>
 * </ul>
 * 端到端测试要凑资源、凑武将、凑城建，一条用例几十行夹具，
 * 而这些规则本身只需要几个数字就能说清楚。
 */
class ArmyStateTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;
    private static final int SLOTS = 3;
    private static final long BATCH_MAX = 100_000L;

    private static ArmyState army() {
        return new ArmyState();
    }

    // ---------- 训练：时间 = 单位时间 × 数量 ----------

    @Test
    @DisplayName("B05 §二：训练时间 = 单位时间 × 数量，批量不等于加速")
    void trainingTimeScalesLinearlyWithCount() {
        ArmyState army = army();
        long finishAt = army.train("unit_infantry_t1", 100L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        assertThat(finishAt - NOW).as("100 个 × 60 秒 = 6000 秒").isEqualTo(6000L * 1000L);
        assertThat(army.totalTraining()).isEqualTo(100L);
        assertThat(army.totalTroops()).as("训练中的兵力还没入账").isZero();
    }

    @Test
    @DisplayName("训练速度作用在<b>批次总时长</b>上：单兵 6 秒 × 10 = 60 秒，减 2% 是 59 秒而不是 60 秒")
    void trainingSpeedAppliesToBatchTotalOnce() {
        ArmyState army = army();
        long finishAt = army.train("unit_infantry_t1", 10L, 6L, 200L, SLOTS, 10_000L, BATCH_MAX, NOW);
        assertThat(finishAt - NOW)
                .as("60 × 0.98 = 58.8 秒 → ceil 成 59。若先按单兵取整（6 × 0.98 = 5.88 → 6 秒再乘 10），"
                        + "加成会被完全吃掉，而这条路径每天在低级兵种上发生无数次")
                .isEqualTo(59L * 1000L);
    }

    @Test
    @DisplayName("加成 100% 也只留 1 秒：0 秒的训练等于没有队列，也没有求助与加速的落点")
    void trainingSpeedNeverReachesZero() {
        ArmyState army = army();
        long finishAt = army.train("unit_infantry_t1", 1L, 1L, 10_000L, SLOTS, 10_000L, BATCH_MAX, NOW);
        assertThat(finishAt - NOW).isEqualTo(1000L);
    }

    @Test
    @DisplayName("带兵上限把训练中的兵力也算进去，否则可以「先塞满队列再换低统率武将」绕过它")
    void trainingTroopsCountAgainstCap() {
        ArmyState army = army();
        army.train("unit_infantry_t1", 90L, 60L, 0L, SLOTS, 100L, BATCH_MAX, NOW);
        assertThat(army.totalTraining()).isEqualTo(90L);

        // 上限 100、已占用 90，再来 20 个就超了 —— 即使这 90 个还在训练、一个都没入账
        assertThatThrownBy(() -> army.canTrain("unit_cavalry_t1", 20L, SLOTS, 100L, BATCH_MAX))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("带兵上限");
        // 正好补齐到上限是允许的
        army.canTrain("unit_cavalry_t1", 10L, SLOTS, 100L, BATCH_MAX);
    }

    @Test
    @DisplayName("已入账的兵力同样计入上限")
    void deployedTroopsCountAgainstCap() {
        ArmyState army = army();
        army.add("unit_infantry_t1", 95L);
        assertThatThrownBy(() -> army.canTrain("unit_cavalry_t1", 10L, SLOTS, 100L, BATCH_MAX))
                .isInstanceOf(BizException.class).hasMessageContaining("带兵上限");
        army.canTrain("unit_cavalry_t1", 5L, SLOTS, 100L, BATCH_MAX);
    }

    @Test
    @DisplayName("队列条数用满后拒绝；同一兵种不能并行两批")
    void queueSlotsAndPerUnitSerializationAreEnforced() {
        ArmyState army = army();
        army.train("unit_infantry_t1", 10L, 60L, 0L, 1, 10_000L, BATCH_MAX, NOW);
        assertThatThrownBy(() -> army.canTrain("unit_cavalry_t1", 10L, 1, 10_000L, BATCH_MAX))
                .isInstanceOf(BizException.class).hasMessageContaining("训练队列已满");

        ArmyState wide = army();
        wide.train("unit_infantry_t1", 10L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        assertThatThrownBy(() -> wide.canTrain("unit_infantry_t1", 10L, SLOTS, 10_000L, BATCH_MAX))
                .isInstanceOf(BizException.class).hasMessageContaining("已在训练中");
        // 额外队列（特权/道具）必须真的放宽上限
        wide.addExtraSlot(1);
        assertThat(wide.extraSlots()).isEqualTo(1);
    }

    @Test
    @DisplayName("超过批上限时拒绝，不静默截断（截断等于扣了钱少给兵）")
    void batchMaxIsEnforced() {
        ArmyState army = army();
        assertThatThrownBy(() -> army.canTrain("unit_infantry_t1", 100_001L, SLOTS, 1_000_000L, BATCH_MAX))
                .isInstanceOf(BizException.class).hasMessageContaining("单次最多训练");
    }

    @Test
    @DisplayName("惰性收割：到点后 collectFinished 把兵入账并释放队列，未到点的一个都不动")
    void finishedTrainingIsHarvestedLazily() {
        ArmyState army = army();
        army.train("unit_infantry_t1", 100L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        // 100 个 × 60 秒 = 6000 秒。这里的常量都带 MS 后缀：训练时长以秒计、时间戳以毫秒计，
        // 两者混用会让「到点了却没入账」看起来像惰性结算坏了，实际只是差了一千倍
        final long durationMs = 100L * 60L * 1000L;

        assertThat(army.collectFinished(NOW + durationMs - 1000L))
                .as("还差 1 秒，不能提前入账").isEmpty();
        assertThat(army.totalTroops()).isZero();

        assertThat(army.collectFinished(NOW + durationMs)).containsExactly("unit_infantry_t1");
        assertThat(army.countOf("unit_infantry_t1")).isEqualTo(100L);
        assertThat(army.usedSlots()).as("队列必须被释放").isZero();
        assertThat(army.collectFinished(NOW + durationMs * 2)).as("重复收割不能重复入账").isEmpty();
        assertThat(army.countOf("unit_infantry_t1")).isEqualTo(100L);
    }

    @Test
    @DisplayName("加速会被剩余时间截断，剩余秒数与进度永不为负、永不超 100%")
    void speedUpIsTruncatedAndNeverNegative() {
        ArmyState army = army();
        army.train("unit_infantry_t1", 10L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);

        assertThat(army.speedUp("unit_infantry_t1", 30L, NOW)).isEqualTo(30L);
        assertThat(army.queue().get("unit_infantry_t1").remainingSeconds(NOW)).isEqualTo(570L);
        // 请求 99999 秒，但只剩 570 秒 ⇒ 只提前 570 秒
        assertThat(army.speedUp("unit_infantry_t1", 99_999L, NOW)).isEqualTo(570L);
        assertThat(army.queue().get("unit_infantry_t1").remainingSeconds(NOW)).isZero();
        assertThat(army.queue().get("unit_infantry_t1").progressFixed(NOW))
                .as("加速到 0 时进度必须是满格，不能 >100%")
                .isEqualTo(com.ironoath.common.num.FixedPoint.SCALE);
        // 再加速一次已经没有剩余时间可减
        assertThat(army.speedUp("unit_infantry_t1", 100L, NOW)).isZero();
    }

    @Test
    @DisplayName("取消训练把任务交回给调用方；已到点的批次直接入账而不是被取消掉")
    void cancelReturnsTaskAndHarvestsIfAlreadyDue() {
        ArmyState army = army();
        army.train("unit_infantry_t1", 25L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        ArmyState.TrainingTask task = army.cancel("unit_infantry_t1", NOW);
        assertThat(task.count()).isEqualTo(25L);
        assertThat(army.usedSlots()).isZero();
        assertThatThrownBy(() -> army.cancel("unit_infantry_t1", NOW))
                .isInstanceOf(BizException.class).hasMessageContaining("无法取消");

        // 已到点却没被收割：直接入账比取消更符合玩家预期，
        // 否则玩家会因为「点取消的那一瞬间刚好训完」而白丢一批兵
        army.train("unit_cavalry_t1", 7L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        assertThatThrownBy(() -> army.cancel("unit_cavalry_t1", NOW + 10 * HOUR))
                .isInstanceOf(BizException.class).hasMessageContaining("已训练完成并已入账");
        assertThat(army.countOf("unit_cavalry_t1")).isEqualTo(7L);
    }

    @Test
    @DisplayName("帮助加速训练：比例乘在原始总时长上，重复帮助不会复利缩水")
    void helpSpeedUpTrainingUsesOriginalDurationAsBase() {
        ArmyState army = army();
        // 10 个 × 100 秒 = 1000 秒，原始总时长记下来就是帮助的基数
        army.train("unit_infantry_t1", 10L, 100L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);

        long perHelp = com.ironoath.common.num.FixedPoint.parse("0.01");
        assertThat(army.speedUpTrainingByRatio("unit_infantry_t1", perHelp, NOW))
                .as("原始 1000 秒的 1%").isEqualTo(10L);
        for (int i = 0; i < 9; i++) {
            army.speedUpTrainingByRatio("unit_infantry_t1", perHelp, NOW);
        }
        // 10 次 × 原始 1000 秒 × 1% = 100 秒。若基数是「当前剩余」，第二次只有 9.9 秒，
        // 10 次之后会多剩几秒 —— 那个差就是复利缩水，也正是上限永远达不到的原因
        assertThat(army.queue().get("unit_infantry_t1").remainingSeconds(NOW))
                .as("基数必须是原始总时长").isEqualTo(900L);

        assertThat(army.speedUpTrainingByRatio("unit_cavalry_t1", perHelp, NOW))
                .as("目标不在队列里（已完成或已取消）时静默返回 0，而不是抛给帮忙的人")
                .isZero();
    }

    @Test
    @DisplayName("帮助加速治疗：基数同样是原始总时长，没在治疗时返回 0")
    void helpSpeedUpTreatmentUsesOriginalDurationAsBase() {
        ArmyState army = army();
        army.admitWounded(Map.of("unit_infantry_t1", 30L), 100L);
        army.startTreatment(600L, Map.of("IRON", 900L), NOW);
        long perHelp = com.ironoath.common.num.FixedPoint.parse("0.01");

        assertThat(army.speedUpTreatmentByRatio(perHelp, NOW)).as("原始 600 秒的 1%").isEqualTo(6L);
        assertThat(army.treatRemainingSeconds(NOW)).isEqualTo(594L);
        // 再帮一次仍按原始 600 秒算，而不是按 594 秒
        assertThat(army.speedUpTreatmentByRatio(perHelp, NOW)).isEqualTo(6L);
        assertThat(army.treatRemainingSeconds(NOW)).isEqualTo(588L);

        ArmyState idle = army();
        assertThat(idle.speedUpTreatmentByRatio(perHelp, NOW)).as("没在治疗时返回 0").isZero();
    }

    // ---------- 医院 ----------

    @Test
    @DisplayName("B05 §1.5：超出医院容量的伤兵直接死亡，容量为 0 时全死")
    void hospitalOverflowKills() {
        assertThat(army().admitWounded(Map.of("unit_infantry_t1", 100L), 0L))
                .as("没有医院 ⇒ 伤兵无处可去 ⇒ 全部阵亡").isEqualTo(100L);

        ArmyState army = army();
        assertThat(army.admitWounded(Map.of("unit_infantry_t1", 100L), 60L)).isEqualTo(40L);
        assertThat(army.totalWounded()).isEqualTo(60L);
        // 容量已满时新到的伤兵全死
        assertThat(army.admitWounded(Map.of("unit_cavalry_t1", 30L), 60L)).isEqualTo(30L);
        assertThat(army.totalWounded()).isEqualTo(60L);
        assertThat(army.wounded()).doesNotContainKey("unit_cavalry_t1");
    }

    @Test
    @DisplayName("伤兵收容按传入顺序，所以「谁被挤死了」是可复现的而不是随机的")
    void hospitalAdmissionOrderIsDeterministic() {
        ArmyState first = army();
        Map<String, Long> incoming = new LinkedHashMap<>();
        incoming.put("unit_infantry_t1", 40L);
        incoming.put("unit_cavalry_t1", 40L);
        first.admitWounded(incoming, 60L);

        ArmyState second = army();
        second.admitWounded(new LinkedHashMap<>(incoming), 60L);
        assertThat(second.wounded()).isEqualTo(first.wounded());
        assertThat(first.wounded().get("unit_infantry_t1")).as("先到的先收满").isEqualTo(40L);
        assertThat(first.wounded().get("unit_cavalry_t1")).as("后到的只收剩余的 20").isEqualTo(20L);
    }

    @Test
    @DisplayName("治疗：开始后不能重复开始，完成后伤兵归队且不受带兵上限约束")
    void treatmentLifecycle() {
        ArmyState army = army();
        army.admitWounded(Map.of("unit_infantry_t1", 30L), 100L);
        assertThatThrownBy(() -> army.startTreatment(0L, Map.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Long> cost = Map.of("IRON", 900L, "GRAIN", 600L);
        long finishAt = army.startTreatment(180L, cost, NOW);
        assertThat(finishAt - NOW).isEqualTo(180L * 1000L);
        assertThatThrownBy(() -> army.startTreatment(180L, cost, NOW))
                .isInstanceOf(BizException.class).hasMessageContaining("已在治疗中");
        assertThat(army.treatOriginalSeconds()).as("原始时长必须与总时长分开存（帮助加速的百分比基数）")
                .isEqualTo(180L);

        assertThat(army.collectTreated(NOW + 179_000L)).as("还差 1 秒不能归队").isEmpty();
        // 归队不受带兵上限约束：这些兵本来就是玩家的，卡上限会造成「治好了却领不出来」的死锁
        Map<String, Long> returned = army.collectTreated(NOW + 180_000L);
        assertThat(returned).containsEntry("unit_infantry_t1", 30L);
        assertThat(army.countOf("unit_infantry_t1")).isEqualTo(30L);
        assertThat(army.totalWounded()).isZero();
        assertThat(army.treatFinishAt()).isNull();
        assertThat(army.treatOriginalSeconds()).as("治疗结束后计时字段要清零").isZero();
    }

    @Test
    @DisplayName("加速治疗同样被剩余时间截断；取消治疗后伤兵留在医院（不治不会死）")
    void treatmentSpeedUpAndCancel() {
        ArmyState army = army();
        army.admitWounded(Map.of("unit_infantry_t1", 30L), 100L);
        army.startTreatment(180L, Map.of("IRON", 900L), NOW);

        assertThat(army.speedUpTreatment(60L, NOW)).isEqualTo(60L);
        assertThat(army.treatRemainingSeconds(NOW)).isEqualTo(120L);
        assertThat(army.speedUpTreatment(99_999L, NOW)).as("只剩 120 秒可减").isEqualTo(120L);
        assertThat(army.treatRemainingSeconds(NOW)).isZero();
        assertThat(army.treatOriginalSeconds()).as("加速不能改变原始时长").isEqualTo(180L);

        ArmyState second = army();
        second.admitWounded(Map.of("unit_infantry_t1", 30L), 100L);
        second.startTreatment(180L, Map.of("IRON", 900L), NOW);
        Map<String, Long> costBack = second.cancelTreatment(NOW);
        assertThat(costBack).as("取消要交回消耗快照，调用方据此算返还").containsEntry("IRON", 900L);
        assertThat(second.totalWounded()).as("伤兵留在医院，不治不会死").isEqualTo(30L);
        assertThat(second.treatFinishAt()).isNull();
        assertThatThrownBy(() -> second.cancelTreatment(NOW))
                .isInstanceOf(BizException.class).hasMessageContaining("无法取消");
    }

    // ---------- 兵力增减的边界 ----------

    @Test
    @DisplayName("扣兵力扣到 0 为止，绝不为负（负兵力会让被打的一方在战斗里反而变强）")
    void deductNeverGoesNegative() {
        ArmyState army = army();
        army.add("unit_infantry_t1", 10L);
        assertThat(army.deduct("unit_infantry_t1", 4L)).isEqualTo(4L);
        assertThat(army.countOf("unit_infantry_t1")).isEqualTo(6L);
        assertThat(army.deduct("unit_infantry_t1", 100L)).as("最多只能扣掉现有的 6 个").isEqualTo(6L);
        assertThat(army.countOf("unit_infantry_t1")).isZero();
        assertThat(army.troops()).as("扣到 0 的兵种应从 map 里移除，否则总兵力统计会算错")
                .doesNotContainKey("unit_infantry_t1");
        assertThat(army.deduct("unit_not_exists", 5L)).as("没有这个兵种就扣 0，不报错").isZero();
    }

    @Test
    @DisplayName("非法入参在调用点就拒绝")
    void rejectsInvalidInput() {
        ArmyState army = army();
        assertThatThrownBy(() -> army.add("", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> army.add("u", 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正");
        assertThatThrownBy(() -> army.deduct("u", -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> army.train("u", 0L, 60L, 0L, SLOTS, 100L, BATCH_MAX, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("必须为正");
        assertThatThrownBy(() -> army.train("u", 1L, 0L, 0L, SLOTS, 100L, BATCH_MAX, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("单位训练时间");
        assertThatThrownBy(() -> army.speedUp("u", 0L, NOW))
                .isInstanceOf(BizException.class).hasMessageContaining("不在训练队列里");
        assertThatThrownBy(() -> army.admitWounded(Map.of("u", -1L), 10L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不得为负");
        assertThatThrownBy(() -> army.admitWounded(Map.of("u", 1L), -1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("容量");
    }

    @Test
    @DisplayName("restore 传入自己的视图不会把数据清空（别名 bug 回归）")
    void restoreIsSafeWhenGivenItsOwnViews() {
        ArmyState army = army();
        army.add("unit_infantry_t1", 40L);
        army.train("unit_cavalry_t1", 7L, 60L, 0L, SLOTS, 10_000L, BATCH_MAX, NOW);
        army.admitWounded(Map.of("unit_archer_t1", 12L), 100L);
        army.startTreatment(60L, Map.of("IRON", 300L), NOW);

        // 「读出 → 只改一项 → 写回」是很自然的写法，而 troops()/queue()/wounded()
        // 返回的都是内部 Map 的不可变视图。曾经的实现先 clear 再拷贝，
        // 于是 clear 把正在读的入参一起清空 —— 不报错，只是数据凭空消失
        army.restore(army.troops(), army.queue(), army.wounded(), NOW + 60_000L,
                army.treatTotalSeconds(), army.treatOriginalSeconds(), army.treatCost(),
                army.extraSlots());

        assertThat(army.countOf("unit_infantry_t1")).as("兵力必须原样保留").isEqualTo(40L);
        assertThat(army.queue()).as("训练队列必须原样保留").containsKey("unit_cavalry_t1");
        assertThat(army.totalWounded()).as("伤兵必须原样保留").isEqualTo(12L);
        assertThat(army.treatCost()).as("治疗消耗快照必须原样保留").containsEntry("IRON", 300L);
        assertThat(army.treatOriginalSeconds()).isEqualTo(60L);
        assertThat(army.treatFinishAt()).isEqualTo(NOW + 60_000L);
    }

    @Test
    @DisplayName("训练任务的进度与剩余时间在边界上都不越界")
    void trainingTaskBoundaries() {
        ArmyState.TrainingTask task = new ArmyState.TrainingTask(
                "unit_infantry_t1", 10L, NOW, NOW + 100_000L, 100L, 100L);
        assertThat(task.remainingSeconds(NOW - 1L)).as("开始前不返回负数").isEqualTo(100L);
        assertThat(task.remainingSeconds(NOW + 200_000L)).as("到点后停在 0").isZero();
        assertThat(task.progressFixed(NOW)).isZero();
        assertThat(task.progressFixed(NOW + 50_000L)).isEqualTo(5_000L);
        assertThat(task.progressFixed(NOW + 999_999L))
                .as("进度不得超过 100%").isEqualTo(com.ironoath.common.num.FixedPoint.SCALE);
        assertThatThrownBy(() -> new ArmyState.TrainingTask("u", 10L, NOW, NOW - 1L, 100L, 100L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("完成时刻");
    }

    @Test
    @DisplayName("自动续训策略随快照往返；老档没有这一项时默认是关的（不会读一次档就开始花钱）")
    void autoTrainPolicySurvivesTheSnapshotAndDefaultsOff() {
        ArmyState army = new ArmyState();
        assertThat(army.autoTrain().enabled()).as("新号默认关").isFalse();

        army.setAutoTrain(AutoTrainPolicy.on("unit_infantry_t1", 120L, 3));
        ArmyState reloaded = ArmyState.fromSnapshot(army.snapshot());
        assertThat(reloaded.autoTrain().enabled()).isTrue();
        assertThat(reloaded.autoTrain().unitId()).isEqualTo("unit_infantry_t1");
        assertThat(reloaded.autoTrain().batchCount()).isEqualTo(120L);
        assertThat(reloaded.autoTrain().batchBudget()).isEqualTo(3);

        // 老档路径：快照里这一项是 null（库里没有那一列）⇒ 当作关
        ArmyState.Snapshot legacy = new ArmyState.Snapshot(army.troops(), army.snapshot().queue(),
                army.wounded(), null, 0L, 0L, Map.of(), 0, null);
        assertThat(ArmyState.fromSnapshot(legacy).autoTrain().enabled())
                .as("老档读出来必须是关的：从没开过自动的号不该因为一次读档就花钱")
                .isFalse();
    }
}
