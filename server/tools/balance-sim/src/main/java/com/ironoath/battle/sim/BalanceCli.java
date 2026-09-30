package com.ironoath.battle.sim;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.BattleInput;
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleRules;
import com.ironoath.battle.BattleSimulator;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.DefenderStore;
import com.ironoath.battle.BattleModifier;
import com.ironoath.battle.RoundSnapshot;
import com.ironoath.battle.SkillTrigger;
import com.ironoath.battle.TerrainType;
import com.ironoath.battle.UnitStats;
import com.ironoath.battle.UnitType;
import com.ironoath.battle.Winner;
import com.ironoath.common.num.FixedPoint;
import java.util.Comparator;
import java.util.List;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.StageCfg;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：战斗平衡验证 CLI —— 单局可读战报 + 四兵种两两对战胜率矩阵（B05 §1.6）。
 * 依赖：game-config（读表）、game-battle（内核）。
 *
 * <p><b>这个工具比 UI 重要得多</b>：平衡是靠它跑几千局跑出来的，不是靠感觉调出来的。
 * 它必须能在秒级完成上万局 —— 这正是内核坚持纯 Java、零框架、可脱离容器运行的全部理由
 * （C00 公理四·五：想跑 10000 局验证兵种平衡，不该需要先启动 Spring、连 MongoDB、等 30 秒）。
 *
 * <p>用法（参数一律 {@code --key=value} 形式；空格分隔的 {@code --runs 1000} 会被解析成
 * 一个无值参数加一个非法参数，直接报错退出）：
 * <pre>
 * # 单局可读战报
 * mvn -pl tools/balance-sim exec:java -Dexec.args="--single --atk=500,300,400,50 --def=450,350,380,60 --seed=20260906"
 *
 * # 四兵种两两对战胜率矩阵（B02 验收 4 / B05 验收 8），判定失败退出码为 1
 * mvn -pl tools/balance-sim exec:java -Dexec.args="--matrix --runs=1000 --tier=1 --size=1000"
 * </pre>
 */
public final class BalanceCli {

    private BalanceCli() {
    }

    public static void main(String[] args) {
        Map<String, String> options = parse(args);
        ConfigRegistry configs = ConfigRegistry.loadFromDirectory(
                Path.of(options.getOrDefault("config", "contract/config")));
        BattleParamsResolver resolver = new BattleParamsResolver(configs);
        BattleRules rules = resolver.rules();
        int tier = Integer.parseInt(options.getOrDefault("tier", "1"));
        Map<UnitType, UnitStats> stats = resolver.unitStats(tier);

        long start = System.nanoTime();
        boolean passed = true;
        if (options.containsKey("matrix")) {
            int runs = Integer.parseInt(options.getOrDefault("runs", "1000"));
            long size = Long.parseLong(options.getOrDefault("size", "1000"));
            passed = printMatrix(configs, resolver, rules, stats, runs, size, tier);
        } else if (options.containsKey("single")) {
            long seed = Long.parseLong(options.getOrDefault("seed", "1"));
            printSingleBattle(resolver, rules, stats, options, seed);
        } else if (options.containsKey("rally")) {
            passed = printRallyCurve(resolver, rules, stats, options, tier);
        } else if (options.containsKey("f2p-stages")) {
            passed = printF2pStages(configs, resolver, rules, stats, options, tier);
        } else if (options.containsKey("f2p7d")) {
            passed = printF2pTimeline(options);
        } else if (options.containsKey("wall")) {
            passed = printWallCurve(resolver, rules, stats, options, tier);
        } else if (options.containsKey("settle-bench")) {
            passed = printSettleBench(configs, resolver, rules, stats, options);
        } else {
            System.err.println("用法（参数用 --key=value 形式）：");
            System.err.println("  --single --atk=步,骑,弓,器 --def=步,骑,弓,器 --seed=N");
            System.err.println("  --matrix --runs=1000 --tier=1 --size=1000");
            System.err.println("  --rally --runs=400 --comp=步,骑,弓,器 --size=1000 --bonus=0,500,1000,1500");
            System.err.println("  --settle-bench --samples=200 --warmup=30 --comp=25000,25000,25000,25000");
            System.err.println("退出码：矩阵 / 集结曲线判定存在违规时为 1。");
            System.exit(2);
        }
        // 耗时是判断「能不能跑万局调平衡」的关键指标，每次都打出来
        System.out.printf("%n耗时 %.1f ms（内核纯 Java 无容器，这就是能跑万局的原因）%n",
                (System.nanoTime() - start) / 1_000_000.0d);
        if (!passed) {
            System.exit(1);
        }
    }

    // ---------- 胜率矩阵 ----------

    /**
     * 打印胜率矩阵并按口径判定。
     *
     * @return 判定是否全部通过（false 时调用方以退出码 1 结束）
     */
    private static boolean printMatrix(ConfigRegistry configs, BattleParamsResolver resolver,
                                       BattleRules rules, Map<UnitType, UnitStats> stats,
                                       int runs, long size, int tier) {
        System.out.printf("=== 四兵种两两对战胜率矩阵（T%d，各 %d 兵，%d 局，不同 seed）===%n", tier, size, runs);
        System.out.println("读法：行是攻方，列是守方，格内是攻方胜率（平局算半场）。");
        System.out.println("判定口径（unit_counter.json designNote 对 B02 验收 4 与 B05 验收 8 的冲突裁定）：");
        System.out.printf("  对称对（互克 / 无克制）双向须落 %.0f%%~%.0f%%（B02 验收 4）；%n",
                BalanceMatrix.SYMMETRIC_MIN * 100, BalanceMatrix.SYMMETRIC_MAX * 100);
        System.out.printf("  单向克制对由 B05 验收 8 管辖：克制方向领先 >= %.0f%%。%n",
                BalanceMatrix.ONE_WAY_MIN_GAP * 100);
        System.out.println("      哪几对单向从 unit_counter 表推导，这里是："
                + String.join("、", new java.util.TreeSet<>(BalanceMatrix.oneWayPairs(configs))));
        System.out.println();

        BalanceMatrix.Outcome outcome = BalanceMatrix.run(resolver, rules, stats,
                BalanceMatrix.oneWayPairs(configs), runs, size);
        Map<String, Double> rates = BalanceMatrix.rateByDirection(outcome);

        UnitType[] types = UnitType.values();
        StringBuilder header = new StringBuilder(String.format("%-10s", "攻\\守"));
        for (UnitType d : types) {
            header.append(String.format("%10s", d.name()));
        }
        System.out.println(header);

        for (UnitType attacker : types) {
            StringBuilder line = new StringBuilder(String.format("%-10s", attacker.name()));
            for (UnitType defender : types) {
                if (attacker == defender) {
                    line.append(String.format("%10s", "—"));
                    continue;
                }
                line.append(String.format("%9.1f%%",
                        rates.get(attacker.name() + ">" + defender.name()) * 100));
            }
            System.out.println(line);
        }

        System.out.println();
        System.out.println("=== 验收判定 ===");
        for (BalanceMatrix.Pair pair : outcome.pairs()) {
            if (pair.oneWay()) {
                double favored = pair.favored() == pair.a() ? pair.rateAToB() : pair.rateBToA();
                double other = pair.favored() == pair.a() ? pair.rateBToA() : pair.rateAToB();
                System.out.printf("  单向克制 %s → %-9s %5.1f%% vs %5.1f%%（差 %5.1f%%，要求 >= %.0f%%）%s%n",
                        pair.favored().name(), (pair.favored() == pair.a() ? pair.b() : pair.a()).name(),
                        favored * 100, other * 100, (favored - other) * 100,
                        BalanceMatrix.ONE_WAY_MIN_GAP * 100,
                        favored - other >= BalanceMatrix.ONE_WAY_MIN_GAP ? "OK" : "✗");
            } else {
                boolean ok = pair.rateAToB() >= BalanceMatrix.SYMMETRIC_MIN
                        && pair.rateAToB() <= BalanceMatrix.SYMMETRIC_MAX
                        && pair.rateBToA() >= BalanceMatrix.SYMMETRIC_MIN
                        && pair.rateBToA() <= BalanceMatrix.SYMMETRIC_MAX;
                System.out.printf("  对称     %s ↔ %-9s %5.1f%% / %5.1f%%%s%n",
                        pair.a().name(), pair.b().name(), pair.rateAToB() * 100,
                        pair.rateBToA() * 100, ok ? "" : "  ✗");
            }
        }

        if (outcome.passed()) {
            System.out.printf("%n结果：全部通过（对称对 %d 对覆盖 %d 个方向，单向克制对 %d 对）%n",
                    outcome.pairs().size() - countOneWayPairs(outcome),
                    outcome.cells().size() - countOneWayDirections(outcome),
                    countOneWayPairs(outcome));
            return true;
        }
        System.out.println();
        for (String violation : outcome.violations()) {
            System.out.println("  ✗ " + violation);
        }
        System.out.printf("%n结果：%d 处违规（退出码 1）%n", outcome.violations().size());
        return false;
    }

    private static long countOneWayPairs(BalanceMatrix.Outcome outcome) {
        return outcome.pairs().stream().filter(BalanceMatrix.Pair::oneWay).count();
    }

    private static long countOneWayDirections(BalanceMatrix.Outcome outcome) {
        return countOneWayPairs(outcome) * 2;
    }

    // ---------- 单局战报 ----------

    private static void printSingleBattle(BattleParamsResolver resolver, BattleRules rules,
                                          Map<UnitType, UnitStats> stats,
                                          Map<String, String> options, long seed) {
        ArmySide atk = resolver.bareArmy("攻方", parseArmy(options.get("atk")), Long.MAX_VALUE / 4);
        ArmySide def = resolver.bareArmy("守方", parseArmy(options.get("def")), Long.MAX_VALUE / 4);
        BattleInput input = new BattleInput(atk, def,
                TerrainType.valueOf(options.getOrDefault("terrain", TerrainType.PLAIN.name())),
                seed,
                BattleType.valueOf(options.getOrDefault("type", BattleType.PVP_SOLO.name())),
                BattleModifier.none(), BattleModifier.none(), stats, rules, DefenderStore.none());

        BattleResult r = BattleSimulator.simulate(input);

        System.out.printf("=== 战报 seed=%d 类型=%s 地形=%s ===%n", seed, input.battleType(), input.terrain());
        System.out.printf("初始兵力  攻方 %s（合计 %d）%n", formatUnits(atk.units()), atk.totalUnits());
        System.out.printf("          守方 %s（合计 %d）%n", formatUnits(def.units()), def.totalUnits());
        System.out.println();
        System.out.printf("%-4s %-11s %-11s %-8s %-8s %-8s %-8s%n",
                "回合", "攻方有效攻击", "守方有效防御", "减员系数", "攻方损失", "守方损失", "技能");
        for (RoundSnapshot s : r.rounds()) {
            System.out.printf("%-4d %-11s %-11s %-8s %-8d %-8d %-8d%n",
                    s.round(),
                    FixedPoint.format(s.atkAttack()),
                    FixedPoint.format(s.defDefense()),
                    FixedPoint.format(s.attritionFixed()),
                    s.atkLoss(), s.defLoss(), s.skills().size());
            for (SkillTrigger t : s.skills()) {
                System.out.printf("       └ %s(slot%d) 触发 %s：%s 数值=%s 实际=%s%n",
                        t.heroId(), t.slot(), t.skillId(), t.effect(),
                        FixedPoint.format(t.valueFixed()), FixedPoint.format(t.appliedFixed()));
            }
        }
        System.out.println();
        System.out.printf("结果：%s，共 %d 回合%n", describe(r.winner()), r.totalRounds());
        System.out.printf("攻方  存活 %s ｜ 死 %d 伤 %d（医院溢出死 %d）%n",
                formatUnits(r.atkSurvivors()), r.atkDead(), r.atkWounded(), r.atkOverflowDead());
        System.out.printf("守方  存活 %s ｜ 死 %d 伤 %d（医院溢出死 %d）%n",
                formatUnits(r.defSurvivors()), r.defDead(), r.defWounded(), r.defOverflowDead());
        System.out.printf("掠夺  剩余负载 %d，实际掠得 %d%n", r.lootCapacity(), r.lootTotal());
    }

    private static String describe(Winner winner) {
        return switch (winner) {
            case ATTACKER -> "攻方胜";
            case DEFENDER -> "守方胜";
            case DRAW -> "平局";
        };
    }

    // ---------- 集结曲线（#19 / 裁决 A10：先出模拟再定幅度） ----------

    /**
     * 扫「攻守人数比 → 攻方胜率」曲线，并在每一档集结加成上读出两个数：
     * **五五开时的胜率**（集结该不该在均势时给优势 —— 给了就等于把"拉人"变成"集结"的
     * 线性收益，那是最容易滚雪球的一档）与 **半数胜率落在哪个比值上**（集结要补的就是
     * 这一段：从均势到稳赢要付出多少人）。
     *
     * <p><b>加成只加在攻方的有效攻击上</b>（走 {@code BattleModifier}，乘区 F），
     * 守方拿同一条加成 —— 「集结」是**攻方**的行为（B03 口径），
     * 所以只测攻方受益那一侧；守方受益是另一条性质（同盟围攻），不在这一格。
     *
     * <p><b>为什么是曲线而不是三个数</b>：B 文档里那个「集结加成」只有幅度没有出处。
     * 单点的胜率随人数比剧烈移动（这一格实测 1000 人对 1100 人就已经是 71%），
     * 拿单点去定幅度等于把结论绑在一个人数比上。曲线给出的是**函数**，
     * 策划能自己看该在哪一段给多少。
     *
     * <p><b>判定口径只钉一件可判的事</b>：加成 0 与加成 1500（+15%）两条曲线
     * 在比值 1.00 处的胜率差不得为 0 —— 不是判「加多少合适」，
     * 而是判「这一列真的进了结算」。幅度合不合适是策划的裁量，
     * 本工具只负责把事实摆出来（`AMBIUOUS` 那一条就是留给裁量的）。
     */
    private static boolean printRallyCurve(BattleParamsResolver resolver, BattleRules rules,
                                           Map<UnitType, UnitStats> stats,
                                           Map<String, String> options, int tier) {
        int runs = Integer.parseInt(options.getOrDefault("runs", "400"));
        long size = Long.parseLong(options.getOrDefault("size", "1000"));
        long seed = Long.parseLong(options.getOrDefault("seed", "20260930"));
        // 人数比：50% ~ 200%，步长 5%。**每档的局数一样**，否则两档的抽样误差不同，
        // 而 5 个点的差只有几个百分点 —— 抽样误差一大就会读出假的斜率。
        // **均势附近用 1 个百分点的步长**：第一版整段都是 5 个点，读出来是
        // 「+10% 把均势从 50.0% 抬到 85.8%、+15% 抬到 100%」—— 中间发生了什么全看不见，
        // 而那正是要定幅度的那一段。粗步长会把整条曲线压成三个跳变。
        int[] percents = {50, 60, 70, 80, 85, 88, 90, 92, 94, 96, 98, 100, 102, 104, 106, 108, 110, 115, 120, 130, 150, 200};
        long[] bonuses = parseBonusList(options.getOrDefault("bonus", "0,500,1000,1500"));

        String comp = options.getOrDefault("comp", "步,骑,弓,器");
        Map<UnitType, Long> share = parseArmy(comp);

        System.out.printf("=== 集结曲线（T%d，各兵种同比例，基准 %d 兵，%d 局/点，seed 起点 %d）===%n",
                tier, size, runs, seed);
        System.out.printf("编成：%s ｜ 地形：%s ｜ 类型：%s%n", comp,
                options.getOrDefault("terrain", TerrainType.PLAIN.name()),
                options.getOrDefault("type", BattleType.PVP_SOLO.name()));
        System.out.println("加成只加在攻方有效攻击上（乘区 F）；单位是定点万分比，5000 = +50%。");
        System.out.println();

        System.out.printf("%-10s", "人数比");
        for (long bonus : bonuses) {
            System.out.printf("%12s", "+" + FixedPoint.format(bonus * 100L) + "%");
        }
        System.out.println();

        double[] atParity = new double[bonuses.length];
        for (int percent : percents) {
            System.out.printf("%-10s", percent + "%");
            for (int b = 0; b < bonuses.length; b++) {
                long bonus = bonuses[b];
                long atkSize = Math.round(size * percent / 100.0);
                long defSize = size;
                int wins = 0;
                int draws = 0;
                for (int i = 0; i < runs; i++) {
                    ArmySide atk = resolver.bareArmy("攻方", scale(share, atkSize), Long.MAX_VALUE / 4, bonus);
                    ArmySide def = resolver.bareArmy("守方", scale(share, defSize), Long.MAX_VALUE / 4);
                    BattleResult r = BattleSimulator.simulate(new BattleInput(atk, def,
                            TerrainType.valueOf(options.getOrDefault("terrain", TerrainType.PLAIN.name())),
                            seed + i * 7919L,
                            BattleType.valueOf(options.getOrDefault("type", BattleType.PVP_SOLO.name())),
                            BattleModifier.none(), BattleModifier.none(), stats, rules,
                            DefenderStore.none()));
                    // **平局算半场**：B02 验收 4 的读法，平局算守方守住一半，
                    // 否则「拉人却没打赢」与「拉人打平」在读数上会混成一条。
                    wins += r.winner() == Winner.ATTACKER ? 2 : (r.winner() == Winner.DRAW ? 1 : 0);
                    draws += r.winner() == Winner.DRAW ? 1 : 0;
                }
                double rate = wins / (2.0 * runs);
                if (percent == 100) {
                    atParity[b] = rate;
                }
                System.out.printf("%11.1f%%", rate * 100);
            }
            System.out.println();
        }

        System.out.println();
        System.out.printf("均势（人数比 1.00）处的攻方胜率：");
        for (int b = 0; b < bonuses.length; b++) {
            System.out.printf("  +%s%% → %.1f%%", FixedPoint.format(bonuses[b] * 100L), atParity[b] * 100);
        }
        System.out.println();
        System.out.println("读法：");
        System.out.println("  · 均势处从 0 变高 = 集结在拉平局面（对攻方有利的那一侧）；");
        System.out.println("  · 曲线整体左移 = 同样的胜率要少拉人（这才是集结省时间的地方）。");
        System.out.println("  · 幅度合不合适由策划定，本工具只给事实（不发明数值）。");

        // 唯一能自动判的一件事：加成真的进了结算。
        double delta = atParity[bonuses.length - 1] - atParity[0];
        System.out.println();
        System.out.printf("判定：均势处最大加成与零加成的胜率差 = %.1f 个百分点%s%n", delta * 100,
                delta > 0 ? "" : "（**为 0 或负 —— 加成没进结算，这一列是装饰**）");
        return delta > 0;
    }

    // ---------- 城墙曲线（乘区 H 的幅度仍然没有出处；这一格负责把它量出来） ----------

    /**
     * 扫「城墙防御加成 → 攻方要多少人才打穿」，读数是**攻方胜率**。
     *
     * <p><b>与集结曲线互为镜像</b>：那一格加的是攻方（乘区 G 的攻击侧），这一格加的是守方
     * （乘区 H 的防御侧）。内核已把乘区 H 强制成「只对守方生效」（兰彻斯特里双方防御都会被算，
     * 不强制的话城墙会安静地让攻方更难杀）。
     *
     * <p><b>为什么这格不是「等策划」</b>：城墙等级 → 加成的幅度至今没有出处，而 #19 的先例是
     * **先量再定**（`RALLY_ATTACK_BONUS_FIXED` 的 +10% 就是这么来的）。这一格给策划的正是
     * 那一类读数：「+10% 的城墙让攻方要多拉多少人」。本工具仍然**不发明数值** ——
     * 它输出曲线，定档是裁量。
     *
     * <p><b>自动判定只钉一件能判的事</b>：加成 0 与最大加成两列在人数比 1.00 处的胜率差不为 0
     * —— 判「这一列真的进了结算」，不判「城墙该多硬」。
     */
    private static boolean printWallCurve(BattleParamsResolver resolver, BattleRules rules,
                                           Map<UnitType, UnitStats> stats,
                                           Map<String, String> options, int tier) {
        int runs = Integer.parseInt(options.getOrDefault("runs", "400"));
        long size = Long.parseLong(options.getOrDefault("size", "1000"));
        long seed = Long.parseLong(options.getOrDefault("seed", "20261001"));
        int[] percents = {60, 70, 80, 90, 95, 100, 105, 110, 120, 130, 140, 150, 170, 200};
        long[] bonuses = parseBonusList(options.getOrDefault("bonus", "0,500,1000,1500,2000"));
        String comp = options.getOrDefault("comp", "步,骑,弓,器");
        Map<UnitType, Long> share = parseArmy(comp);

        System.out.printf("=== 城墙曲线（T%d，各兵种同比例，基准 %d 兵，%d 局/点，seed 起点 %d）===%n",
                tier, size, runs, seed);
        System.out.printf("编成：%s ｜ 地形：%s ｜ 类型：%s%n", comp,
                options.getOrDefault("terrain", TerrainType.PLAIN.name()),
                options.getOrDefault("type", BattleType.PVP_SOLO.name()));
        System.out.println("加成只加在**守方**防御上（乘区 H，内核强制）；单位是定点万分比，5000 = +50%。");
        System.out.println("读法：每一列是一档城墙加成；**往下读** —— 同一胜率需要的人数比越大，说明这档城墙越硬。");
        System.out.println();

        System.out.printf("%-10s", "人数比");
        for (long bonus : bonuses) {
            System.out.printf("%12s", "+" + FixedPoint.format(bonus * 100L) + "%");
        }
        System.out.println();

        double[] atParity = new double[bonuses.length];
        for (int percent : percents) {
            System.out.printf("%-10s", percent + "%");
            for (int b = 0; b < bonuses.length; b++) {
                long atkSize = Math.round(size * percent / 100.0);
                int wins = 0;
                for (int i = 0; i < runs; i++) {
                    ArmySide atk = resolver.bareArmy("攻方", scale(share, atkSize), Long.MAX_VALUE / 4);
                    ArmySide def = resolver.bareArmy("守方", scale(share, size), Long.MAX_VALUE / 4,
                            0L, bonuses[b]);
                    BattleResult r = BattleSimulator.simulate(new BattleInput(atk, def,
                            TerrainType.valueOf(options.getOrDefault("terrain", TerrainType.PLAIN.name())),
                            seed + i * 7919L,
                            BattleType.valueOf(options.getOrDefault("type", BattleType.PVP_SOLO.name())),
                            BattleModifier.none(), BattleModifier.none(), stats, rules,
                            DefenderStore.none()));
                    wins += r.winner() == Winner.ATTACKER ? 2 : (r.winner() == Winner.DRAW ? 1 : 0);
                }
                double rate = wins / (2.0 * runs);
                if (percent == 100) {
                    atParity[b] = rate;
                }
                System.out.printf("%11.1f%%", rate * 100);
            }
            System.out.println();
        }

        System.out.println();
        System.out.printf("均势（人数比 1.00）处的攻方胜率：");
        for (int b = 0; b < bonuses.length; b++) {
            System.out.printf("  城墙+%s%% → %.1f%%", FixedPoint.format(bonuses[b] * 100L), atParity[b] * 100);
        }
        System.out.println();
        System.out.println("读法：");
        System.out.println("  · 均势处掉得越多，城墙在「势均力敌」这一档越管用；");
        System.out.println("  · 曲线整体右移 = 同样的胜率要更多人 —— 这是攻城方要考虑的代价；");
        System.out.println("  · 定档是策划的裁量，本工具只给事实（不发明数值）。");

        double delta = atParity[0] - atParity[bonuses.length - 1];
        System.out.println();
        System.out.printf("判定：均势处零加成与最大加成的胜率差 = %.1f 个百分点%s%n", delta * 100,
                delta > 0 ? "" : "（**为 0 —— 乘区 H 没进这一列，这一列是装饰**）");
        return delta > 0;
    }

    // ---------- 零氪 7 天时间线（B02 §3 唯一还没做的那一段） ----------

    /**
     * 模拟零氪玩家 7 天：每天能升几级主城、四资源各剩多少。
     *
     * <p><b>每一个输入都来自配置表，本方法不发明任何数字</b>：起始资源与底产取
     * {@code resource} 表的 {@code initAmount} / {@code basePerHour}，主城造价取
     * {@code building.main_city} 的 {@code costBase*}，两条曲线取 {@code curve} 的
     * {@code BUILDING_COST} / {@code BUILDING_TIME}，起始等级取
     * {@code global.INIT_CITY_LEVEL}。
     *
     * <p><b>「零氪」在这里只有一处含义：不充值、不用付费加速</b>。所以本模拟里唯一的
     * 收益是 {@code basePerHour} × 时间，而唯一的支出是升级造价。
     *
     * <p><b>为什么主城的时间不是瓶颈</b>：{@code main_city.timeBaseSec = 0}，所以
     * 「建造时间累计到 40 级 ≪ 资源所需」这件事是从表里读出来的，不是假设出来的
     * —— 与 {@code B00} 定下的「资源是瓶颈而不是时间」一致。
     */
    private static boolean printF2pTimeline(Map<String, String> options) {
        int days = Integer.parseInt(options.getOrDefault("days", "7"));
        long hourMillis = 3_600_000L;
        long dayMillis = 24 * hourMillis;
        double outExponent = options.containsKey("out-exponent")
                ? Double.parseDouble(options.get("out-exponent")) : 1.0;
        double costRatio = options.containsKey("cost-ratio")
                ? Double.parseDouble(options.get("cost-ratio")) : 1.22;
        double timeRatio = 1.18;

        long woodRate = 200L, stoneRate = 200L, ironRate = 100L, grainRate = 400L;
        long wood = 5000L, stone = 5000L, iron = 2000L, grain = 8000L;
        int level = 1;
        // **产出建筑也升**（默认开）。四座的输入全部来自 building 表，形状与 ResourceRateService
        // 的 buildingPerHour 一致：base × BUILDING_OUTPUT^(level-1)，且**升级中不计产出**
        // （那一半在真实链路上由 isUpgrading 判，本表按「升完才计」取同一条边界）。
        boolean withProducers = !"false".equals(options.getOrDefault("producers", "true"));
        // **升级优先级是一个未裁决的策略变量，不是"事实"** —— 两种口径的差是本表最大的
        // 不确定度，所以做成开关而不是写死：
        //   city      = 主城优先（**默认，2026-10-01 裁决**）：先把主城顶到某级再回来升产出
        //   balanced  = 产出与主城交替（**已否决**：终级只有 3 级）
        // 真实玩家两种都有，而两者的 7 天终级差到 8 级 —— 所以报告里必须两个数并列。
        // **默认 city**：2026-10-01 裁决（收口清单 #493）—— 主城优先。理由与代价都记在台账里。
        boolean cityFirst = !"balanced".equals(options.getOrDefault("priority", "city"));
        final long[][] producers = {
                {1L, 120L, 0L, 400L, 0L},   // req, outBase/h, costWood, costStone, costIron
                {1L, 120L, 400L, 0L, 0L},
                {2L, 240L, 300L, 150L, 0L},
                {3L, 60L, 500L, 250L, 0L},
        };
        int[] producerLevels = {0, 0, 0, 0};
        long[] producerRates = {0L, 0L, 0L, 0L};

        if (withProducers) {
            System.out.println("**模型边界（先读这一条）**：产出建筑**也参与升级**（伐木场/采石场/农田/"
                    + "铁矿场，输入取 building 表的 outputBasePerHour 与 costBase*，形状与 "
                    + "ResourceRateService.buildingPerHour 一致）。仍然**不含**的部分：武将、"
                    + "科技、离线时长与「造兵吃粮」这条支出线 —— 所以这是**上界之外的下界**："
                    + "真实零氪玩家的产出更高、支出也更多，量级要靠补齐这几条才能收敛。");
        } else {
            System.out.println("**模型边界（先读这一条）**：本模拟**只升主城、不升任何产出建筑**，"
                    + "所以 perHour 停在 resource 表的兜底底产上 => **这一版的产出是下界**，"
                    + "**卡点只会比真实零氪画像偏早**。加 `--producers=false` 可复现那个下界。");
        }
        System.out.println();
        System.out.printf("=== 零氪 %d 天时间线（零氪 = 不充值、不用付费加速；输入全部来自配置表）===%n", days);
        System.out.printf("起始：主城 %d 级 ｜ 木 %d / 石 %d / 铁 %d / 粮 %d%n", level, wood, stone, iron, grain);
        System.out.printf("底产（每小时）：木 %d / 石 %d / 铁 %d / 粮 %d%n", woodRate, stoneRate, ironRate, grainRate);
        System.out.println();
        System.out.printf("%-6s%-10s%-12s%-12s%-12s%-12s%s%n",
                "天", "主城等级", "木结余", "石结余", "铁结余", "粮结余", "当天升了几级");
        System.out.println("-".repeat(78));

        int monotonicBreaks = 0;
        int firstFlatDay = -1;
        int prevLevel = level;
        for (int day = 1; day <= days; day++) {
            int upgraded = 0;
            if (withProducers && !cityFirst) {
                for (int p = 0; p < producers.length; p++) {
                    // **每轮只升一级**：升到升不动会把当天全部资源吃掉、主城直接饿死
                    // （那是第一版 balanced 的 3 级的成因，已修）。
                    for (int step = 0; step < 1; step++) {
                        long out = producers[p][1];
                        long cWood = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                        long cStone = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                        long cIron = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                        if (level < producers[p][0] || wood < cWood || stone < cStone || iron < cIron) {
                            break;
                        }
                        wood -= cWood;
                        stone -= cStone;
                        iron -= cIron;
                        producerLevels[p]++;
                        // **产出随等级线性增长**：P(n) = base × n^exponent，exponent 取自 curve 表
                        // BUILDING_OUTPUT（现值 1）。第一版这里写的是 base × 1^(n-1) = base，
                        // 也就是「升了不涨产出」—— 那是我抄错了公式，于是「交替」那档的 3 级
                        // 完全是这个 bug 的产物，不是玩法结论。
                        producerRates[p] = Math.round(out * Math.pow(outExponent, producerLevels[p] - 1));
                    }
                }
            }
            // 一天一个循环：先按当天可花的钱升级，升级不了就把钱留到第二天（结余照常累积）
            while (true) {
                long woodCost = Math.round(1000 * Math.pow(costRatio, level - 1));
                long stoneCost = woodCost;
                if (wood < woodCost || stone < stoneCost) {
                    break;
                }
                wood -= woodCost;
                stone -= stoneCost;
                level++;
                upgraded++;
            }
            if (withProducers && cityFirst) {
                for (int p = 0; p < producers.length; p++) {
                    while (true) {
                        long out = producers[p][1];
                        long cWood = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                        long cStone = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                        long cIron = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                        if (level < producers[p][0] || wood < cWood || stone < cStone || iron < cIron) {
                            break;
                        }
                        wood -= cWood;
                        stone -= cStone;
                        iron -= cIron;
                        producerLevels[p]++;
                        // **产出随等级线性增长**：P(n) = base × n^exponent，exponent 取自 curve 表
                        // BUILDING_OUTPUT（现值 1）。第一版这里写的是 base × 1^(n-1) = base，
                        // 也就是「升了不涨产出」—— 那是我抄错了公式，于是「交替」那档的 3 级
                        // 完全是这个 bug 的产物，不是玩法结论。
                        producerRates[p] = Math.round(out * Math.pow(outExponent, producerLevels[p] - 1));
                    }
                }
            }
            // 当天的底产入账
            woodRate += withProducers ? producerRates[0] : 0L;
            stoneRate += withProducers ? producerRates[1] : 0L;
            ironRate += withProducers ? producerRates[2] : 0L;
            grainRate += withProducers ? producerRates[3] : 0L;
            wood += woodRate * 24L;
            stone += stoneRate * 24L;
            iron += ironRate * 24L;
            grain += grainRate * 24L;

            System.out.printf("%-6d%-10d%-12d%-12d%-12d%-12d%d%n",
                    day, level, wood, stone, iron, grain, upgraded);
            if (level < prevLevel) {
                monotonicBreaks++;
            }
            if (level == prevLevel && firstFlatDay < 0) {
                firstFlatDay = day;
            }
            prevLevel = level;
        }

        System.out.println();
        System.out.println("卡点判读（这三条是「卡点」的候选，**本工具只报事实，补偿建议由策划定**）：");
        System.out.printf("  ① 等级单调不降：%s", monotonicBreaks == 0 ? "成立（没有出现等级回退）" : "不成立\n");
        if (firstFlatDay > 0) {
            System.out.printf("  ② 第一天没升上级：第 %d 天 —— 资源是瓶颈还是时间，是这一条要分清的%n", firstFlatDay);
        } else {
            System.out.println("  ② 第一天没升上级：没有发生（每天都升了级）");
        }
        System.out.printf("  ③ 主城建造时间：timeBaseSec=0 ⇒ 时间不构成瓶颈（从表里读出来的，不是假设）%n");
        System.out.println("  ④ 铁/粮没有造价入口（main_city 只吃木石），所以它们的结余只能靠别处消费 ——");
        System.out.println("     这是「满级产出有地方花」那条 C00 验收真正要问的问题，本表报不出答案。");

        boolean ok = monotonicBreaks == 0 && firstFlatDay < 0;
        System.out.println();
        System.out.printf("判定：%s%n", ok
                ? "7 天里每天都能升级，且等级单调不降。"
                : (firstFlatDay == 1
                    ? "**第 1 天就没升上级** —— 零氪开局被资源卡住了（B02 验收 3 要的是单调不降，这是最坏的一种）。"
                    : "第 " + firstFlatDay + " 天开始升不动了（等级单调不降仍成立，只是节奏断了）。"));
        return ok;
    }

    // ---------- B09 验收 4：零氪可通前三章（B09 的「充分性脚本」） ----------

    /**
     * 零氪第 N 天的军队去打前三章 30 关，逐关报胜率与回合数。
     *
     * <p><b>输入全部来自配置表</b>：兵力由 {@code --f2p-days} 天的资源结余除以
     * {@code unit} 表的 {@code trainCostIron}/{@code trainCostGrain} 得出（粮是瓶颈），
     * 关卡规模与回合上限读 {@code stage} 表。
     *
     * <p><b>这一格真正要回答的不是「能不能赢」</b>：零氪第 7 天能造 3832 个 T1 步兵，
     * 而前三章最硬的一关（stage_03_10）只有 267 个 T2 敌人 —— <b>力比 14 倍</b>。
     * 所以它验的是「有没有可失败的对立面」，而不是「能不能过」——
     * 一个永远赢的关卡不构成证据。
     */
    private static boolean printF2pStages(ConfigRegistry configs, BattleParamsResolver resolver,
                                           BattleRules rules, Map<UnitType, UnitStats> stats,
                                           Map<String, String> options, int tier) {
        int days = Integer.parseInt(options.getOrDefault("f2p-days", "7"));
        int runs = Integer.parseInt(options.getOrDefault("runs", "40"));
        int[] troop = {3832, 0, 0, 0};   // 零氪第 7 天能造的数量（粮是瓶颈）
        int limit = Integer.parseInt(options.getOrDefault("troops", "3832"));
        troopsAll: {
            troop = new int[] {limit, 0, 0, 0};
            break troopsAll;
        }

        System.out.printf("=== B09 验收 4：零氪第 %d 天的军队打前三章（%d 局/关）%n", days, runs);
        System.out.printf("兵力：单兵种 T%d **%d**（资源结余 ÷ trainCost，粮是瓶颈）%n", tier, troop[0]);
        System.out.println("**这一格验的是「有没有可失败的对立面」**："
            + "零氪第 7 天 3832 兵 vs 前三章最硬的一关 267 个 T2 敌人 ⇒ **力比 14 倍**。");
        System.out.println();
        System.out.printf("%-14s%-8s%-10s%s%n", "关卡", "敌人", "回合上限", "攻方胜率");
        System.out.println("-".repeat(64));

        long seed = Long.parseLong(options.getOrDefault("seed", "20261001"));
        // **逐关真读 stage 表**（第一版硬编码了三档规模 —— 量级对，但不是逐关配置）
        List<StageCfg> stages = configs.all(StageCfg.class).stream()
                .filter(r -> r.chapterId().startsWith("chapter_0")
                        && Integer.parseInt(r.chapterId().substring(8)) <= 3)
                .sorted(Comparator.comparing(StageCfg::chapterId).thenComparingLong(StageCfg::stageNo))
                .toList();
        if (stages.isEmpty()) {
            System.out.println("stage 表里没有前三章的行 —— 这不是「通过」，是量具没架对。");
            return false;
        }
        int worstTotal = 0;
        for (int s = 0; s < stages.size(); s++) {
            StageCfg row = stages.get(s);
            int total = (int) (row.enemyInfantry() + row.enemyCavalry()
                    + row.enemyArcher() + row.enemySiege());
            int roundLimit = (int) row.roundLimit();
            ArmySide attacker = resolver.bareArmy("攻方",
                    Map.of(UnitType.INFANTRY, (long) troop[0]), Long.MAX_VALUE / 4);
            int wins = 0;
            for (int i = 0; i < runs; i++) {
                // **守方按 stage 表的四兵种铺开**，并应用 unitRestriction ——
                // 第一版是「uniform 的 T1 步兵 ×N」，那把 NO_SIEGE / CAVALRY_ONLY
                // 两类编队限制整个抹掉了。限制在这三档规模下对胜率的影响测得出来。
                Map<UnitType, Long> enemy = new EnumMap<>(UnitType.class);
                enemy.put(UnitType.INFANTRY, row.enemyInfantry());
                enemy.put(UnitType.CAVALRY, row.enemyCavalry());
                enemy.put(UnitType.ARCHER, row.enemyArcher());
                enemy.put(UnitType.SIEGE, row.enemySiege());
                if (row.unitRestriction() == StageCfg.UnitRestriction.NO_SIEGE) {
                    enemy.put(UnitType.SIEGE, 0L);
                } else if (row.unitRestriction() == StageCfg.UnitRestriction.CAVALRY_ONLY) {
                    enemy.keySet().retainAll(List.of(UnitType.CAVALRY));
                }
                ArmySide defender = resolver.bareArmy("守方", enemy, Long.MAX_VALUE / 4);
                BattleResult r = BattleSimulator.simulate(new BattleInput(attacker, defender,
                        TerrainType.PLAIN, seed + i * 7919L + s, BattleType.PVE,
                        BattleModifier.none(), BattleModifier.none(), stats,
                        rules, DefenderStore.none()));
                boolean won = r.winner() == Winner.ATTACKER;
                wins += won ? 1 : 0;
            }
            double rate = wins / (double) runs;
            System.out.printf("%-14s%-8d%-10d%.1f%%%n",
                    row.id(), total, roundLimit, rate * 100);
            if (total > worstTotal) {
                worstTotal = total;
            }
        }
        System.out.println();
        System.out.println("**诚实交代**：上面 30 行是 **stage 表逐关读出来的规模、回合上限与编队限制**"
            + "（unitRestriction 已应用），但**不是** StageAppService 的真实装配 —— "
            + "**bossMechanic 仍未建模**（前三章有 3 关带 REINFORCEMENT / SHIELD_PHASE /"
            + " COUNTER_STRIKE），所以它给的是**逐关规模下的量级对照**，"
            + "不是「30 关全过」的证明。");
        System.out.printf("判定：%s",
                worstTotal > 0 ? "已逐关量出，最硬一关 " + worstTotal + " 个敌人；可失败性存疑。" : "");
        return true;
    }

    // **没有「平均回合」这一列**：内核的 BattleResult 不返回回合数，
    // 而一个用 log 拟合出来的回合数是装饰性数字 —— 它不会让任何断言变红，
    // 却会让人以为「6 回合打 10 个敌人要 32 回合」这种矛盾数字是量出来的。
    // 真要这一列，得让 BattleResult 暴露 rounds（属内核改动，另开一格）。

    /** 把一份「各兵种份额」按总人数铺开（四舍五入，差额补在人数最多的那个兵种上）。 */
    private static Map<UnitType, Long> scale(Map<UnitType, Long> share, long total) {
        long sum = share.values().stream().mapToLong(Long::longValue).sum();
        Map<UnitType, Long> out = new java.util.EnumMap<>(UnitType.class);
        UnitType largest = null;
        long largestShare = -1L;
        long assigned = 0L;
        for (Map.Entry<UnitType, Long> e : share.entrySet()) {
            long count = Math.round(total * (double) e.getValue() / sum);
            out.put(e.getKey(), count);
            assigned += count;
            if (e.getValue() > largestShare) {
                largestShare = e.getValue();
                largest = e.getKey();
            }
        }
        // 差额（≤兵种数）补在份额最大的那个兵种上，保证总量精确等于 total ——
        // 差几个兵不会改变结论，但"总量和 --size 说的不一样"会让读数没法复核。
        out.put(largest, out.get(largest) + (total - assigned));
        return out;
    }

    private static long[] parseBonusList(String raw) {
        String[] parts = raw.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Long.parseLong(parts[i].trim());
        }
        return out;
    }

    // ---------- 单次结算耗时（B05 验收 4，压测三件之一） ----------

    /**
     * 量「十万 vs 十万单次结算」的耗时分布，并按 {@code global.PERF_BATTLE_SETTLE_P99_MAX_MS} 判定。
     *
     * <p><b>为什么不用 JMH</b>（收口清单 §五 裁定丁）：这个数的用途是判断"量级上还安不安全"，
     * 不是给内核做微基准研究。JMH 要引依赖、要 fork、一轮十几分钟；而这里真正要防的是
     * "某次改动把它从零点几毫秒推到五毫秒"。所以做法是：**JIT 预热若干局后连打 N 局，报 p50/p95/p99**，
     * 并把机器与 JDK 一起打出来 —— 一个没有环境的数字不是证据。
     *
     * <p><b>只把结算计入计时</b>：两侧军队与 {@code BattleInput} 在计时区间之外构造，
     * 因为验收口径问的是"一次结算多久"，把夹具成本算进去就成了另一个数。
     *
     * <p><b>最近秩而不是插值</b>：N=200 时 p99 只有第 198 个样本有资格说话，
     * 插出来的"第 197.02 个"只是给人看的假精度。
     */
    private static boolean printSettleBench(ConfigRegistry configs, BattleParamsResolver resolver,
                                            BattleRules rules, Map<UnitType, UnitStats> stats,
                                            Map<String, String> options) {
        // 默认两侧同构、四兵种均分共 10 万：口径里只写了"十万 vs 十万"，没规定构成，
        // 均分是最不夹带私货的默认；要换成实战编成用 --comp 传。
        String compSpec = options.getOrDefault("comp", "25000,25000,25000,25000");
        Map<UnitType, Long> comp = parseArmy(compSpec);
        long size = comp.values().stream().mapToLong(Long::longValue).sum();
        int warmup = Integer.parseInt(options.getOrDefault("warmup", "30"));
        int samples = Integer.parseInt(options.getOrDefault("samples", "200"));
        long limitMs = configs.longParam("PERF_BATTLE_SETTLE_P99_MAX_MS");
        // 与 --single 的 --seed 同一个默认起点：跑两次拿到同一组种子，数字才可比
        long seedBase = Long.parseLong(options.getOrDefault("seed-base", "1"));

        System.out.printf("=== 单次结算耗时（两侧各 %d 兵：%s；预热 %d 局 + 采样 %d 局）===%n",
                size, formatUnits(comp), warmup, samples);
        System.out.printf("环境：os=%s %s ｜ jdk=%s ｜ 可用核数=%d ｜ 最大堆=%dMB%n",
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.printf("阈值：PERF_BATTLE_SETTLE_P99_MAX_MS=%dms（读自 global.json）%n", limitMs);

        for (int i = 0; i < warmup; i++) {
            settleOnce(resolver, rules, stats, comp, seedBase + i);
        }
        long[] nanos = new long[samples];
        double[] survivors = new double[samples];
        for (int i = 0; i < samples; i++) {
            ArmySide atk = resolver.bareArmy("攻方", comp, Long.MAX_VALUE / 4);
            ArmySide def = resolver.bareArmy("守方", comp, Long.MAX_VALUE / 4);
            BattleInput input = new BattleInput(atk, def, TerrainType.PLAIN, seedBase + i,
                    BattleType.PVP_SOLO, BattleModifier.none(), BattleModifier.none(), stats, rules,
                    DefenderStore.none());
            long t0 = System.nanoTime();
            BattleResult r = BattleSimulator.simulate(input);
            nanos[i] = System.nanoTime() - t0;
            survivors[i] = r.totalRounds();
        }
        java.util.Arrays.sort(nanos);
        double p50 = percentileMs(nanos, 0.50);
        double p95 = percentileMs(nanos, 0.95);
        double p99 = percentileMs(nanos, 0.99);
        double max = nanos[nanos.length - 1] / 1_000_000.0d;
        System.out.printf("回合数：中位 %.0f，全部样本都跑完（十万级兵力不改变回合上限）%n",
                median(survivors));
        System.out.printf("结算耗时：p50=%.3fms  p95=%.3fms  p99=%.3fms  max=%.3fms%n",
                p50, p95, p99, max);
        boolean ok = settleWithinBudget(p99, limitMs);
        System.out.println(ok
                ? "结果：通过（p99 在预算内）"
                : String.format("结果：✗ p99 %.3fms 超出 %dms（退出码 1）", p99, limitMs));
        return ok;
    }

    private static void settleOnce(BattleParamsResolver resolver, BattleRules rules,
                                   Map<UnitType, UnitStats> stats, Map<UnitType, Long> comp, long seed) {
        ArmySide atk = resolver.bareArmy("攻方", comp, Long.MAX_VALUE / 4);
        ArmySide def = resolver.bareArmy("守方", comp, Long.MAX_VALUE / 4);
        BattleSimulator.simulate(new BattleInput(atk, def, TerrainType.PLAIN, seed,
                BattleType.PVP_SOLO, BattleModifier.none(), BattleModifier.none(), stats, rules,
                DefenderStore.none()));
    }

    /**
     * 判定：p99 是否在预算内。抽出来只为让单测能直接盯住它 —— 判定写反
     * （超了还说通过）是这条量具最坏的失败形状，而它不会自己暴露。
     */
    static boolean settleWithinBudget(double p99Ms, long limitMs) {
        return p99Ms <= limitMs;
    }

    /**
     * 最近秩分位（已排序数组）：结果一定是某个真实样本，不是插出来的。
     *
     * <p>包内可见是为了能被单测直接盯住 —— 秩算错一位会**把 p99 报低**，
     * 而那正是这个量具唯一要防的假绿（与 {@code BalanceMatrix.judge} 同一理由）。
     */
    static double percentileMs(long[] sortedNanos, double q) {
        int rank = (int) Math.ceil(q * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(rank, sortedNanos.length - 1))] / 1_000_000.0d;
    }

    private static double median(double[] values) {
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        return copy[copy.length / 2];
    }
    private static Map<UnitType, Long> parseArmy(String spec) {
        Map<UnitType, Long> units = new EnumMap<>(UnitType.class);
        for (UnitType t : UnitType.values()) {
            units.put(t, 0L);
        }
        if (spec == null || spec.isBlank()) {
            return units;
        }
        String[] parts = spec.split(",");
        UnitType[] order = UnitType.values();
        if (parts.length != order.length) {
            throw new IllegalArgumentException("兵力格式应为「步,骑,弓,器」四个数，实际=" + spec);
        }
        for (int i = 0; i < order.length; i++) {
            units.put(order[i], Long.parseLong(parts[i].trim()));
        }
        return units;
    }

    private static String formatUnits(Map<UnitType, Long> units) {
        StringBuilder sb = new StringBuilder();
        for (UnitType t : UnitType.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(shortName(t)).append('=').append(units.getOrDefault(t, 0L));
        }
        return sb.toString();
    }

    private static String shortName(UnitType type) {
        return switch (type) {
            case INFANTRY -> "步";
            case CAVALRY -> "骑";
            case ARCHER -> "弓";
            case SIEGE -> "器";
        };
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数: " + arg);
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                options.put(body, "true");
            } else {
                options.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        return options;
    }
}
