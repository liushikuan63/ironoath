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
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.EquipCfg;
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
        } else if (options.containsKey("determinism")) {
            passed = printDeterminism(resolver, rules, stats, options, tier);
        } else if (options.containsKey("f2p-stages")) {
            passed = printF2pStages(configs, resolver, rules, stats, options, tier);
        } else if (options.containsKey("f2p7d")) {
            passed = printF2pTimeline(configs, options);
        } else if (options.containsKey("wall")) {
            passed = printWallCurve(resolver, rules, stats, options, tier);
        } else if (options.containsKey("settle-bench")) {
            passed = printSettleBench(configs, resolver, rules, stats, options);
        } else {
            System.err.println("用法（参数一律 --key=value 形式；空格分隔的 `--runs 1000` 会被解析成两个开关）：");
            System.err.println();
            System.err.println("【B02 经济 / 进度读数的主入口】");
            System.err.println("  --f2p7d                 零氪时间线（天级）。**B02 全部「实测 N 级 / 溢出 X」都出自它**");
            System.err.println("    --days=7              跑多少天（默认 7）");
            System.err.println("    --cap=true|false      是否算仓容截断与各类扩建（默认 true；false = 只算裸产出）");
            System.err.println("    --cap-base=N          仓容基数（默认读 building 表 capBase，现值 8000）");
            System.err.println("    --base-rate=R         产出倍率（默认 1.0）");
            System.err.println("    --producers=true|false 产出建筑是否参与升级（默认 true）");
            System.err.println("    --priority=city|balanced  主城优先还是产出建筑优先（默认 city）");
            System.err.println("    --dims=a,b,c          计入哪几维（默认 cap,builds,forge,troops）");
        System.err.println("    --seat-by=rate|level|index  产出建筑「先升哪一座」的口径（#685）：");
        System.err.println("                            rate=当前产量最低者先升（默认，与 --priority city 同口径）");
        System.err.println("                            level=等级最低者先升（拉平等级而非产量）");
        System.err.println("                            index=原样保留数组顺序（旧的「先到先赢」，作对照组）");
            System.err.println("    --builds-gate=ids     建造那维开哪几座（默认 none，见 #543/#544 裁决：");
            System.err.println("                         新号阶段先攒料升主城。传 hospital,academy,stable,");
            System.err.println("                         embassy,drill_ground 可开门对照）");
            System.err.println("    --tech-level=N        科技档位：把 tech 表每条 *_OUTPUT 行当作 N 级（默认 0 = 不计）");
            System.err.println("                         ⚠️ 读数是**上界**不是玩家画像：真实玩家要逐级解锁、还要花时间");
            System.err.println("    --population=N        队伍统帅值（默认 46 = hero 表 command 下界，B00 §三 新号赠 1 名）");
            System.err.println("                         带兵上限 = 统帅值 × TROOP_PER_COMMAND（默认 5），**不是 N 本身**");
            System.err.println("    --train-slots=N       训练队列槽位（默认 1 = 表里的 TRAIN_QUEUE_SLOTS）");
            System.err.println("    --train-unit=id       造哪个兵（默认 unit_infantry_t1；单价读 unit 表）");
            System.err.println("    --out-exponent=R      产出随等级的增长指数（默认读 curve.BUILDING_OUTPUT）");
            System.err.println("    --cost-ratio=R        造价随等级的增长比率（默认读 curve.BUILDING_COST）");
            System.err.println("    --f2p-days=N          零氪天数（--f2p-stages 用）");
            System.err.println();
            System.err.println("【战斗矩阵 / 曲线】");
            System.err.println("  --single --atk=步,骑,弓,器 --def=步,骑,弓,器 --seed=N");
            System.err.println("  --matrix --runs=1000 --tier=1 --size=1000");
            System.err.println("  --rally  --runs=400 --comp=步,骑,弓,器 --size=1000 --bonus=0,500,1000,1500");
            System.err.println("  --wall   --runs=400 --comp=步,骑,弓,器 --size=1000");
            System.err.println("  --settle-bench --samples=200 --warmup=30 --comp=25000,25000,25000,25000");
            System.err.println("    --type=NAME            战斗类型（PVP_SOLO 等，取 BattleType 枚举名，默认 PVP_SOLO）");
            System.err.println("    --troops=N            兵力上限（默认 6833，见 #564）");
            System.err.println();
            System.err.println("【其它】");
            System.err.println("  --f2p-stages --terrain=... --cases=...   零氪分阶段读数");
            System.err.println("  --determinism --runs=N --seed-base=N       同输入同输出的确定性自检");
            System.err.println("  --config=PATH                            指定配置目录（默认 contract/config）");
            System.err.println();
            System.err.println("退出码：0 正常；1 判定存在违规（矩阵 / 集结曲线 / 判定不通过）；2 用法错误。");
            System.err.println("⚠️ `--f2p7d` 自己的判定不通过时也退 1（输出末尾有「判定：…」），那不是构建失败。");
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
    private static boolean printF2pTimeline(ConfigRegistry configs, Map<String, String> options) {
        int days = Integer.parseInt(options.getOrDefault("days", "7"));
        long hourMillis = 3_600_000L;
        long dayMillis = 24 * hourMillis;
        // ⚠️ **这三个数原先一个是写死的、一个默认取错（#671）**：
        //   `outExponent` 默认 **1.0**、而线上 `ResourceRateService:131` 读 `curve.BUILDING_OUTPUT`
        //   的 `exponentFixed()` = **1.08** ⇒ 模拟器的产出曲线是 `base × level^1.0`（线性），
        //   线上是 `base × level^1.08` ⇒ **40 级时产量差 38%**（40 vs 40^1.08 ≈ 55.2）。
        //   而这行代码下面 26 行处的仓容（#665）**已经在用 1.08** ⇒ 同一份代码里
        //   「仓容 1.08、产出 1.0」自相矛盾，那个矛盾本身就是判据。
        //   `costRatio` 数值 1.22 与 `curve.BUILDING_COST.ratio` 相同，但那是「今天一致」——
        //   它有 **13 个使用点**（仓库/产出建筑/主城造价），表一改就全漂移。
        //   `timeRatio` 原为写死 1.18，**但整份文件里从未被使用**（死变量）⇒ 直接删。
        // 定点数（真实值 ×10000）转 double 用项目既有的 `/ 10000.0d` 写法（见 ArmyEndpointTest:309）。
        // 三个 CLI 覆盖开关（`--out-exponent` / `--cost-ratio`）保留，量具仍可被探针拧。
        final com.ironoath.common.config.CurveParams outputCurve =
                configs.curve("BUILDING_OUTPUT");
        final com.ironoath.common.config.CurveParams costCurve =
                configs.curve("BUILDING_COST");
        double outExponent = options.containsKey("out-exponent")
                ? Double.parseDouble(options.get("out-exponent"))
                : outputCurve.exponentFixed() / 10000.0d;
        double costRatio = options.containsKey("cost-ratio")
                ? Double.parseDouble(options.get("cost-ratio"))
                : costCurve.ratioFixed() / 10000.0d;
        // `--base-rate` 只在 CLI 里存在（默认 1.0 = 不缩放）：量「底产要缩到几成，仓储才不再满」。
        final double baseRate = Double.parseDouble(options.getOrDefault("base-rate", "1.0"));

        // 底产：**从 building 表读** `outputBasePerHour`（lumber_camp / quarry / iron_mine / farm），
        // **不再写死**。前一版写死 200/200/100/400 是我编的 —— 表里真实值是
        // **120 / 120 / 60 / 240**，低 40% ⇒ 此前所有溢出读数都被高估。
        // `--base-rate` 缩放的就是这四个数。
        long woodRate = 0L, stoneRate = 0L, ironRate = 0L, grainRate = 0L;
        for (var row : configs.all(BuildingCfg.class)) {
            // `outputBasePerHour` 是**可选列** —— main_city / warehouse 那些行没有产出，
            // 取值返回 null（生成物对 `?LONG` 列不填默认值）。不判空会 NPE。
            Long perBox = row.outputBasePerHour();
            long per = perBox == null ? 0L : perBox;
            if (per <= 0L) {
                continue;
            }
            // outputResource 是指向 resource 表 id 的**外键字符串**（表里存的是**大写**
            // `WOOD`/`STONE`/`IRON`/`GRAIN`），不是枚举。第一版按小写 switch，
            // 四档全不匹配 ⇒ 底产读成 0 ⇒ 那次跑出来的「溢出全 0、主城只到 4 级」
            // 是**读数为 0 造成的假象**，不是结论。
            switch (row.outputResource()) {
                case "WOOD" -> woodRate += per;
                case "STONE" -> stoneRate += per;
                case "IRON" -> ironRate += per;
                case "GRAIN" -> grainRate += per;
                default -> { }
            }
        }
        woodRate = Math.round(woodRate * baseRate);
        stoneRate = Math.round(stoneRate * baseRate);
        ironRate = Math.round(ironRate * baseRate);
        grainRate = Math.round(grainRate * baseRate);

        // ---------- 科技加成（#636，甲：先补齐模型的第一刀）----------
        // **倍率一律从 `tech` 表读，不在这里写死任何百分比**（铁律「待裁决不发明」）。
        // 算法复用 `com.ironoath.common.config.TechBonusCore` —— 与线上 `TechEffects` 同一份，
        // 免得模拟器与线上各算一套、悄悄漂移（#592/#594 连续踩的病根）。
        //
        // `--tech-level=N`：把**所有** `*_OUTPUT` 属性的科技行统一当作 N 级。N=0 = 一行都没研究过
        // ⇒ 必须与本开关出现之前的读数**逐位相同**（对照组，见下面的自检）。
        // ⚠️ **这个读数的性质是「上界」不是「真实路径」**：真实玩家按科技树逐级解锁、
        // 还要花钱与等时间；而这里直接把每行顶到同一级 ⇒ 它回答的是
        // 「**如果产出科技满级，时间线会走到哪**」，用来给「120 天 17 级」那个下界定上界，
        // 不能当玩家画像。报告里两处都写明这一句。
        int techLevel = Integer.parseInt(options.getOrDefault("tech-level", "0"));
        long woodTechPct = 0L, stoneTechPct = 0L, ironTechPct = 0L, grainTechPct = 0L;
        if (techLevel > 0) {
            var rows = new java.util.ArrayList<com.ironoath.common.config.TechBonusCore.Row>();
            for (var row : configs.all(com.ironoath.config.cfg.TechCfg.class)) {
                rows.add(new com.ironoath.common.config.TechBonusCore.Row(
                        row.id(), row.effectAttr().name(), row.effectValue()));
            }
            // 全部科技行都顶到 techLevel：levelOf 不看 id，一律返回 techLevel
            java.util.function.ToIntFunction<String> uniform = id -> techLevel;
            woodTechPct = com.ironoath.common.config.TechBonusCore.totalPercent(
                    rows, com.ironoath.config.cfg.TechCfg.EffectAttr.WOOD_OUTPUT.name(), uniform);
            stoneTechPct = com.ironoath.common.config.TechBonusCore.totalPercent(
                    rows, com.ironoath.config.cfg.TechCfg.EffectAttr.STONE_OUTPUT.name(), uniform);
            ironTechPct = com.ironoath.common.config.TechBonusCore.totalPercent(
                    rows, com.ironoath.config.cfg.TechCfg.EffectAttr.IRON_OUTPUT.name(), uniform);
            grainTechPct = com.ironoath.common.config.TechBonusCore.totalPercent(
                    rows, com.ironoath.config.cfg.TechCfg.EffectAttr.GRAIN_OUTPUT.name(), uniform);
            // 定点万分比：Σ effectValue × level 本身就是万分比 ⇒ 乘数 = 1 + pct/10000
            woodRate = Math.round(woodRate * (10_000L + woodTechPct) / 10_000L);
            stoneRate = Math.round(stoneRate * (10_000L + stoneTechPct) / 10_000L);
            ironRate = Math.round(ironRate * (10_000L + ironTechPct) / 10_000L);
            grainRate = Math.round(grainRate * (10_000L + grainTechPct) / 10_000L);
        }
        System.out.printf("底产（读自 building 表）：木 %d / 石 %d / 铁 %d / 粮 %d 每小时"
                + "（已乘 --base-rate %s）%n", woodRate, stoneRate, ironRate, grainRate,
                options.getOrDefault("base-rate", "1.0"));
        // **初始资源也从 resource 表读**（#599）：原来写死 5000/5000/2000/8000，与 resource.json 的
        // initAmount 一致 —— 但那是「今天一致」。#594~#598 已经把 capBase / initCap / producers /
        // 仓库与主城造价都接到表上了，只剩这一行自己记一份，改表就会静默按旧初始量跑。
        // 上面那四行 woodRate/stoneRate/... 早就读表了，这一行是唯一的例外。
        long wood = configs.get(com.ironoath.config.cfg.ResourceCfg.class, "WOOD").initAmount();
        long stone = configs.get(com.ironoath.config.cfg.ResourceCfg.class, "STONE").initAmount();
        long iron = configs.get(com.ironoath.config.cfg.ResourceCfg.class, "IRON").initAmount();
        long grain = configs.get(com.ironoath.config.cfg.ResourceCfg.class, "GRAIN").initAmount();
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
        // ⚠️ **选座口径开关（#685）**：`--seat-by` 决定「先升哪一座」的比较量。
        // 默认值 `rate` 与 `--priority city` 那一支同口径（#648/#649 裁决：「当前产量最低的先升」）；
        // `level` = 拉平**等级**；`index` = **原样保留数组顺序**（#548 点名要消除的「先到先赢」）。
        // ⚠️ 存在的理由：`--priority balanced` 那一支（!cityFirst）原本**一直**是数组顺序，
        // 与 city 支口径不一致（#684），而两者的读数在 capBase=32000 下又被仓容上限
        // 抹平成同一个数（#680）⇒ 差异一直没被看见。
        // **加这个开关不是为了改行为，是为了让三种口径可对照。**
        final String seatBy = options.getOrDefault("seat-by", "rate");
        // ---------- 仓储上限截断（#507）----------
        // **第一版没有这一维**，于是 45 天报出「铁 607 万」—— 而游戏里铁上限只有 5 万。
        // 截断是真规则：ResourceSettlement.settle 装满后 overflow = output - room，
        // **产出被丢弃**（不是排队、不是溢出到别处）。容量 = initCap + warehouse.capBase × 等级。
        // **initCap 从 resource 表读**（#596）：原来硬编码 {20000, 20000, 10000, 30000}，与 resource.json 的
        // initCap 一致（#596 现跑核对过四项全对），但两边漂移时模拟器仍照常输出读数 —— 与 #592 的 capBase 同一种病。
        final long[] initCap = {
                configs.get(com.ironoath.config.cfg.ResourceCfg.class, "WOOD").initCap(),
                configs.get(com.ironoath.config.cfg.ResourceCfg.class, "STONE").initCap(),
                configs.get(com.ironoath.config.cfg.ResourceCfg.class, "IRON").initCap(),
                configs.get(com.ironoath.config.cfg.ResourceCfg.class, "GRAIN").initCap(),
        };   // 木 石 铁 粮
        long barracksLevel = 0L;      // 兵营等级（造兵前置：requireMainLevel=3）
        long standing = 0L;            // 在编兵力（造满 500 即停 —— 队列上限）
        long troopsMade = 0L;
        final long initAmount = 0L;                                  // 起始资源见下面的初值
        // **--cap-base 的默认值从配置表读**（#594）：原来硬编码 1000，而 building.json 的
        // warehouse.capBase 已经是 8000 —— 两边漂移时模拟器照常输出一整套读数，却全是按 1000 算的
        // （#592/#593 连续两轮的结论就建立在错的输入上）。命令行显式传 --cap-base 仍可覆盖，
        // 那是**有意的**扫档手段，不是漂移；门禁 scripts/check-balance-sim-capbase.sh 盯着默认值。
        long capBaseFromCfg = 0L;
        try {
            capBaseFromCfg = configs.get(com.ironoath.config.cfg.BuildingCfg.class, "warehouse").capBase();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("读不到 warehouse.capBase，仓容截断没法算：" + ex.getMessage(), ex);
        }
        final long woodCapBase = Long.parseLong(options.getOrDefault("cap-base", Long.toString(capBaseFromCfg)));
                              // warehouse.capBase（表里 1000），可调以便扫档
        // 仓库与主城的造价行（#598）：下面算升级花费时读它们的 costBaseWood / costBaseStone，
        // 不再在两处各写一份字面量。取不到就抛，而不是静默用一个默认值继续跑。
        BuildingCfg warehouseCfg;
        BuildingCfg mainCityCfg;
        try {
            warehouseCfg = configs.get(BuildingCfg.class, "warehouse");
            mainCityCfg = configs.get(BuildingCfg.class, "main_city");
        } catch (RuntimeException ex) {
            throw new IllegalStateException("读不到 warehouse / main_city 的造价行，升级花费没法算：" + ex.getMessage(), ex);
        }
        final long[] warehouseLevels = {0L, 0L, 0L, 0L};
        // ⚠️ **仓容公式原先是线性的 `initCap + capBase × 仓库等级`（#665），与线上不一致**。
        // 线上 `ResourceRateService`（L156-160）走的是 `Formula.buildingOutput(cfg.capBase(), level, outputExponent)`
        // —— 即 **`capBase × level^1.08`**（`building.json` 的 designNote 写明：
        // 「容量走 BUILDING_OUTPUT 曲线（POWER，指数 1.08）…**『容量 ÷ 每小时产量』这个比值与等级无关，
        // 恒等于 capBase/120 ≈ 8.3 小时**」，而 `warehouse.why` 里 #592 那次定档写的也是
        // 「20000 + 1000×**16^1.08**」）。⇒ 线性公式让「能囤几小时」**随等级递减**，
        // 而线上是恒定；40 级时线上 8000×40^1.08 ≈ **429848**、线性只有 **320000**（差 **34%**）。
        // ⚠️ 这不是「capBase 取多少」那种数值口径（#592 只核了 base 从表读，没核公式形状），
        // 而是**公式形状**脱钩 ⇒ 溢出与升仓节奏都被低估。
        // ⚠️ `Formula` 在 game-core 的 `com.ironoath.core.formula`，`FixedPoint` 在 game-common 的 `com.ironoath.common.num`；
        // balance-sim 本来就依赖这两个模块（#644 用了 `HeroCalculator`、#641 用了 `TechBonusCore` 同理）。
        // `Formula.buildingOutput` 的指数是**定点**（与 ResourceRateService 单一来源同口径）；
        // 而 `outExponent` 是 double（供 Math.pow 用）⇒ 这里取 curve 表的原值，不从 double 反推。
        final long outExponentFixed = configs.curve("BUILDING_OUTPUT").exponentFixed();
        final java.util.function.LongUnaryOperator warehouseCapPer = (lv) ->
                // ⚠️ **必须是「套 `of` + 外面 `round`」这一对，缺一个就差 10000 倍（#676 更正 #674）**：
                // 线上 `ResourceRateService` 的仓容口径是
                //     `FixedPoint.round(Formula.buildingOutput(FixedPoint.of(cfg.capBase()), level, exp))`
                //     = round((capBase × 10000) × level^1.08) ÷ 10000 = **capBase × level^1.08**
                // 而 `Formula.buildingOutput` → `FixedPoint.powerLaw` **返回定点**（`FixedPoint.round` 的
                // javadoc 写明「定点转 long 整数，15000 ⇒ 2」，它内部 `divideHalfUp(fixed, SCALE)` ÷10000）。
                // ⇒ 所以两侧的 `of` 与 `round` 必须成对出现：**少了 `of` 会把 8000 当 0.8 定点（容量≈0），
                // 少了 `round` 会把定点值当普通整数用（大 10000 倍）**。
                // ⚠️ #674 我在这里只去掉了 `of`、没补 `round`，得出「线上仓容 9.6 亿」这个**错误结论**：
                // 实测 cap=961,831,548 = `FixedPoint.of(8000) × 10^1.08`（定点值），
                // 真正的根因是**模拟器缺 `round`**，而**线上三处本来就是对的**（#675 已回滚线上改动并回绿）。
                // ⇒ 正确写法与线上逐字同构。
                // ⚠️ 另：`buildingOutput` 要求等级 >= 1（它会校验），而仓库等级初始就是 0 ——
                // 线上碰不到是因为 `ResourceRateService` 外面套了 `b.level() > 0` 的判断，未建不贡献容量。
                // ⇒ 这里照抄同一口径：**等级 0 贡献 0 容量**（不回退线性，那会又变成两套公式）。
                lv <= 0L ? 0L
                        : com.ironoath.common.num.FixedPoint.round(
                                com.ironoath.core.formula.Formula.buildingOutput(
                                        com.ironoath.common.num.FixedPoint.of(woodCapBase),
                                        (int) lv, outExponentFixed));
        long[] overflow = {0L, 0L, 0L, 0L};
        boolean withCap = !"false".equals(options.getOrDefault("cap", "true"));
        // `--dims` 只在 CLI 里存在：**关掉某一维再跑**，用来看它对读数的贡献。
        // #529 那个「B00 写第 7 天 13 级、现跑 4 级」要定位是哪一维造成的，就是靠这个开关逐个试。
        // 写法：`--dims=cap,builds,forge`（不写 = 全开）；写 `none` = 全关。
        java.util.Set<String> dims = new java.util.HashSet<>(List.of(
                options.getOrDefault("dims", "cap,builds,forge,troops").split(",")));
        boolean dimBuilds = dims.contains("builds");
        // `--builds-gate`：只建这些（#543 的待裁决项要读「一座不建」的影响面）
        // **裁决 #549（2026-10-01）**：零氪前 7 天**不建**那四座零产出建筑。
        // 读数依据：**全建 45 天主城 8 级 / 默认一座不建 12 级**（#652 现跑，对账全程平）。
// ⚠️ 原来这里记的是「全建 5 级 / 一座不建 11 级（#544）」—— **那是 #651 修好「累计」之前的口径**：
// 那时 `forgeLevel` / `buildLevel` 每天归零、装备与建筑被按 0 级造价重造，
// 铁被白嫖掉、主城才勉强爬到 5 级。#651 之后数字变了，就地更正而不删原文。
        // 理由：它们解锁的能力（治疗上限 / 骑兵 / 科技 / 联盟）在新号阶段一样都用不上
        // （无战损、无行军、无外交），而建造是**不可逆**的资源投入 ——
        // 先攒料升主城、有了战损再补医院，是同一笔钱的两种花法。
        java.util.Set<String> buildsGate = new java.util.HashSet<>(List.of(
                options.getOrDefault("builds-gate", "none").split(",")));
        boolean dimForge = dims.contains("forge");
        boolean dimTroops = dims.contains("troops");
        // **producers 从 building 表读**（#596）：原来四行字面量，#596 现跑逐项核对过与表一致（req/outBase/costWood/costStone/costIron 四项×四行全对），
        // 但那是「今天一致」，不是「不会漂移」—— 与 capBase、initCap 同一种病，一并接到表上。
        // 顺序保持不变（伐木场/采石场/农田/铁矿场），只有值改成读来的。
        String[] producerIds = {"lumber_camp", "quarry", "farm", "iron_mine"};
        long[][] prodRows = new long[producerIds.length][6];   // req, outBase/h, costWood, costStone, costIron, maxLevel   // req, outBase/h, costWood, costStone, costIron
        for (int i = 0; i < producerIds.length; i++) {
            BuildingCfg pc = configs.get(BuildingCfg.class, producerIds[i]);
            prodRows[i][0] = pc.requireMainLevel();
            prodRows[i][1] = pc.outputBasePerHour() == null ? 0L : pc.outputBasePerHour();
            prodRows[i][2] = pc.costBaseWood();
            prodRows[i][3] = pc.costBaseStone();
            prodRows[i][4] = pc.costBaseIron();
            // ⚠️ **第 6 列 = `maxLevel`（#673）**：产出建筑此前**没有等级上限**，
            // 实测粮建筑能升到 51 级（`building.json` 的 `maxLevel` 是 40），
            // 而线上 `ResourceRateService` 受 `building.maxLevel`封顶 ⇒ 与 #670 主城那处同源。
            prodRows[i][5] = pc.maxLevel();
        }
        final long[][] producers = prodRows;
        int[] producerLevels = {0, 0, 0, 0};
        long[] producerRates = {0L, 0L, 0L, 0L};

        if (withProducers) {
            System.out.println("**模型边界（先读这一条）**：产出建筑**也参与升级**（伐木场/采石场/农田/"
                    + "铁矿场，输入取 building 表的 outputBasePerHour 与 costBase*，形状与 "
                    + "ResourceRateService.buildingPerHour 一致）。仍然**不含**的只有一条："
                    + "**「造兵吃粮」这条支出线**（造兵量已建模、吃粮还没扣）"
                    + "（科技见 --tech-level、武将见 --population，两条都已接上）"
                    + " —— 所以这是**上界之外的下界**："
                    + "真实零氪玩家的产出更高、支出也更多，量级要靠补齐这一条才能收敛。");
            // ⚠️ **「离线时长」与「武将」都曾被写进上面那句「不含」里，两条都是错的**，
            // 而且是**打给读者看的那一句** —— 读者会据此以为离线不产资源、以为没算武将。
            // ① 离线（#645）：`ResourceSettlement.settle` 的产出是
            //    `theoreticalOutput(perHour, elapsedMs) = perHour × elapsedMs / 3600000`，
            //    `elapsedMs = now - lastSettle`，**全程无封顶**（`global` 表里与离线有关的只有
            //    `OFFLINE_REPORT_MIN_IDLE_MINUTES` / `OFFLINE_REPORT_MIN_ITEMS` 两个**弹窗阈值**
            //    和 `NET_OFFLINE_QUEUE_MAX` 行军队列上限，**没有产出封顶参数**）；而本表每日
            //    `+= perHour × 24`，`perHour × 86400000 / 3600000 = perHour × 24`
            //    ⇒ **离线口径本来就完全一致**，玩家离开多久都照算，不需要额外建模。
            // ② 武将（#644）：`--population` 已接 `HeroCalculator.troopCap`，
            //    带兵上限 = 统帅值 × `TROOP_PER_COMMAND`，从表读。
        } else {
            System.out.println("**模型边界（先读这一条）**：本模拟**只升主城、不升任何产出建筑**，"
                    + "所以 perHour 停在 resource 表的兜底底产上 => **这一版的产出是下界**，"
                    + "**卡点只会比真实零氪画像偏早**。加 `--producers=false` 可复现那个下界。");
        }
        if (techLevel > 0) {
            // ⚠️ 这一段**不是**免责声明，是读数的性质说明 —— 少了它，报告会被当成玩家画像引用。
            System.out.printf("**科技口径（--tech-level=%d）**：把 tech 表里每一条 `*_OUTPUT` 行"
                    + "**统一当作 %d 级**，加成 = Σ(effectValue × %d)，倍率 = 1 + 万分比/10000。"
                    + "加成逐项：木 +%.2f%% / 石 +%.2f%% / 铁 +%.2f%% / 粮 +%.2f%%。%n",
                    techLevel, techLevel, techLevel,
                    woodTechPct / 100.0, stoneTechPct / 100.0,
                    ironTechPct / 100.0, grainTechPct / 100.0);
            System.out.println("⚠️ **这个数是「上界」不是玩家画像**：真实玩家要按科技树逐级解锁、"
                    + "还要花钱与等时间（`TECH_TIME` 比率 1.28），这里直接把每行顶到同一级。"
                    + "它回答的是「产出科技满级时时间线会走到哪」，用来给那个下界**定上界**；"
                    + "`--tech-level=0` 是对照组，两者的差就是科技这条线的全部贡献。");
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
        // 具名扣料（#537 / #541）：**名字写在这一行旁边**，不靠「插入顺序」编号；
        // **声明在每日循环之外** —— 它原先在循环内，每轮重建，于是最后一天打印的
        // 「累计」其实是**当日**（#541 的根因：表头写累计、数据是当日，读数错一个量级）。
        class Ledger {
            final Map<String, Long> tags = new java.util.TreeMap<>();
            void spend(String res, String who, long amount) {
                tags.merge(res + "/" + who, amount, Long::sum);
            }
        }
        final Ledger ledger = new Ledger();

        // ⚠️ **这七个「累计」计数器与「各建筑当前等级」表必须声明在每日循环之外**（#651）。
        // 原来它们写在 `for (day)` **里面**（L692/693/770/771/772/814/815）⇒ **每天全部归零**，
        // 而报告却打「装备强化累计吃铁 / 建造累计吃粮 / 建造累计吃木石铁」——
        // **同一次运行里那个「累计吃铁」在 840 / 1540 / 1865 之间来回跳**，就是它每天重置的证据。
        // ⚠️ 更严重的不只是数字不好看：`buildLevel` / `forgeLevel` 记的是**各建筑当前等级**，
        // 每天归零等于**每天认为那些建筑还是 0 级、每天都按最低造价重复升一遍**（#651）。
        // ⇒ 这也是 #650 写下的「铁恒定 1675、造兵那 3000 铁永远排不上队」的机制。
        long buildWood = 0L, buildStone = 0L, buildIron = 0L, buildGrain = 0L;
        long cityWood = 0L, cityStone = 0L;   // 主城升级花费（#532 对账用）
        long forgeIron = 0L;
        int forgePieces = 0;
        Map<String, Long> forgeLevel = new java.util.HashMap<>();
        Map<String, Long> buildLevel = new java.util.HashMap<>();
        for (int day = 1; day <= days; day++) {
            int upgraded = 0;
            // ---------- 仓库升级（第二版才补上的一维）----------
            // 第一版只加了「截断」却**没让玩家升仓库** ⇒ warehouseLevels 恒为 0
            // ⇒ cap 恒等于 initCap ⇒ 溢出必然在第 3 天发生，而那个「仓库扩容追不上产出」
            // 的判断**建立在「仓库根本没被考虑」之上**，是错的。
            // warehouse：requireMainLevel=2、cost 600木+300石、capBase 1000/级、maxLevel 40。
        // 每一处资源扣除自报（#534）：**别靠 grep 猜，让它自己报花了多少**
        final Map<String, Long> spendTags = new java.util.TreeMap<>();
        final double BUILD_COST_RATIO = 1.22;   // curve.BUILDING_COST.ratio
            if (withCap) {
                // 兵营：与主城等级同步推进（requireMainLevel=3），本版不单独花资源升它
                while (barracksLevel < Math.min(40L, level - 2L)) {
                    barracksLevel++;
                }
                // **只有仓库快满了才升**（#531）：`RESOURCE_PROTECT_RATIO = 0.20`
                // 是「保留 20% 余量」的出处置 ⇒ 触发点是**当前量 ≥ 80% cap**。
                // 之前这一维「能升就升」，于是在木石只占容量 15% 时也在升仓库 ——
                // 而仓库给的是容量、容量根本没满 ⇒ **纯浪费**：
                // 实测 7 天里木石从约 40320 掉到约 6500，几乎全被仓库与建造吃掉，
                // 主城因此只到 4~5 级。**真实玩家不会在 15% 时升仓库**，这是模型缺陷不是机制。
                final long WH_TRIGGER_NUM = 80L;    // 80% cap（= 1 - RESOURCE_PROTECT_RATIO）
                final long WH_TRIGGER_DEN = 100L;
                // **门要对四种资源都判**（#560）：原来只判木与石（`[0]`/`[1]`），
                // 而截断那侧（结算处）对**四种**都算 ⇒ **粮与铁满了仓库也不升**。
                // 症状：粮从第 5 天起长期满在 `initCap` 30 000 上、溢出 23.7 万，
                // 而仓库一级没升（`capBase 1000`/级本可以扩到 7 万）。
                // **这是「门控与实体的资源集合不一致」**，属模型缺陷不是数值口径。
                boolean anyNearFull = false;
                long[] resNow = {wood, stone, iron, grain};
                for (int rr = 0; rr < 4; rr++) {
                    long capR = initCap[rr] + warehouseCapPer.applyAsLong(warehouseLevels[rr]);
                    if (resNow[rr] * WH_TRIGGER_DEN >= capR * WH_TRIGGER_NUM) {
                        anyNearFull = true;
                        break;
                    }
                }
                boolean woodNearFull = anyNearFull;   // 保留原变量名，条件已并入 anyNearFull
                boolean stoneNearFull = anyNearFull;
                while (level >= 2 && warehouseLevels[0] < 40L
                        && (woodNearFull || stoneNearFull)) {
                    // 仓库造价也从表读（#598）：原来写死 600/300，与 warehouse 行的 costBaseWood /
                    // costBaseStone 一致 —— 但那是「今天一致」。改表不动这里，读数就静默按旧值算。
                    long wc = Math.round(warehouseCfg.costBaseWood() * Math.pow(costRatio, warehouseLevels[0]));
                    long ws = Math.round(warehouseCfg.costBaseStone() * Math.pow(costRatio, warehouseLevels[0]));
                    if (wood < wc || stone < ws) {
                        break;
                    }
                    wood -= wc;
                    ledger.spend("wood", "warehouse-upgrade", wc);
                    stone -= ws;
                    ledger.spend("stone", "warehouse-upgrade", ws);
                    buildWood += wc;
                    buildStone += ws;
                    // **仓库一级同时扩四种资源的容量**（#560）：原来只 `warehouseLevels[0]++`
                    // （只涨木），于是**石/铁/粮的容量永远不涨** ——
                    // 而 `capBase` 那一列对四种资源是同一个值（表里 1000），
                    // 语义就是「每级每种 +1000」。症状：粮从第 5 天起长期满在 30 000、
                    // 45 天溢出 23.7 万，而仓库一级都没涨过粮的容量。
                    // **模型缺陷（容量语义与实现不一致），不是数值口径。**
                    for (int wr = 0; wr < 4; wr++) {
                        warehouseLevels[wr]++;
                    }
                }
            }
            // ---------- 第四维：装备强化吃铁（#518 / #519 / #522）----------
        // 排在**仓储截断之前** —— 放在之后铁已被 `overflow` 丢弃，这一维永远吃不到东西。
        // 口径**照抄 `EquipForgeCostCalibrationTest`**（同口径才量的是同一件事）：
        //   铁耗(第 n 次) = (might+command+wisdom) × EQUIP_FORGE_COST.base × ratio^(n)
        // base 与 ratio **都是定点**（70 → 700000、1.22 → 12200）。
        // 排序：**裁决 #524（2026-10-01）—— 零氪玩家的资源优先级是「先建全，后点装备」**。
        // 这一维因此排在**建造之后**。原来它排最前、把铁吃干净，挤得 `stable` 与
        // `drill_ground` 永远建不起来（那两座各要铁 300 / 100）。
        // 理由：零氪的真实目标是推战力/推关，而建造是升级的前置（主城 > 训练营 > 兵），
        // 先建才能开下一条线；且与 B00「战力才是目的」一致 —— 「优先点满一件装备」那组
        // 读数（粮缺口 173798）看着更小，实测是把 `hospital`/`embassy` 挤掉而改建
        // 便宜的 `drill_ground`，**实际战力更低**。
        // **排序改了（#522）**：这一维现在排在**建造之后** —— 原来它排最前、把铁吃干净，
        // 挤得 `stable` 与 `drill_ground` 永远建不起来（那两座各要铁 300 / 100）。
        // 真实玩家是「先把该建的建了、剩下的铁才去点装备」，这个顺序比「无条件优先」更真。
        // ⚠️ 仍是行为假设：**余额才点**，不设每日上限（上限属玩法口径，未裁决）。
        // ⚠️ **这两个值原先是写死的 `FORGE_BASE = 700000L` / `FORGE_RATIO = 12200L`**（#664）：
        // ① **违反 `CurveCfg` 的铁律** —— 该类的类注释明写「代码中不得出现任何曲线常量（铁律 1）」；
        // ② **表一改就漂移**，而模拟器照常输出一整套读数（与 #592 capBase、#594 initCap、#641 科技加成同族）；
        // ③ **指数这一项：一度以为也错了，实测是我自己错了** —— 表 `EQUIP_FORGE_COST` 的公式是
        //    `F(n) = (行属性总和 × 70) × 1.22^(n-1)`，而线上 `EquipForgeCostCalibrationTest` 传
        //    `geometric(rowBaseFixed, curve.ratio(), level - 1)`，看着像该照抄那个 `- 1`；
        //    **但两边的等级语义不同**：校准测试的 `level` 是 **1-based**，
        //    而这里的 `lvl` 是 **0-based**（`forgeLevel.getOrDefault(eq.id(), 0L)`）
        //    ⇒ **指数本来就该是 `lvl`，原来没错**。我照抄 `- 1` 之后第一次跑就抛
        //    `geometric 的指数不得为负：-1`（首日 `lvl = 0`）⇒ 若只盯读数不看退出码，
        //    就会把「读数变了」当成「修好了」，而那其实是我自己引入的红。
        //    ⇒ 本格只修 ①②（改成读表），**不动指数**。
        final com.ironoath.common.config.CurveParams forgeCurve =
                configs.curve("EQUIP_FORGE_COST");
        if (withCap && dimForge) {
            for (var eq : configs.all(EquipCfg.class)) {
                // `rarity` 是**枚举 EquipCfg.Rarity**（不是字符串）——
                // #519 记的「N 档一件都没进循环」就是这个：用 `"N".equals(eq.rarity())`
                // 恒为 false，整条消费线静默地一条都没跑。
                if (eq.rarity() != EquipCfg.Rarity.N) {
                    continue;                    // 只算 N 档：B20 §五② 的开局 4 槽
                }
                long points = eq.might() + eq.command() + eq.wisdom();
                long forgeMax = eq.forgeMax();
                if (points <= 0L || forgeMax <= 0L) {
                    continue;
                }
                long lvl = forgeLevel.getOrDefault(eq.id(), 0L);
                while (lvl < forgeMax) {
                    long cost = FixedPoint.round(FixedPoint.geometric(
                            points * forgeCurve.baseFixed(), forgeCurve.ratioFixed(), (int) lvl));
                    if (iron < cost) {
                        break;                     // 铁不够就等下一天（优先点能升的那一件）
                    }
                    iron -= cost;
                    forgeIron += cost;
                    lvl++;
                }
                if (lvl > 0L) {
                    forgePieces++;
                }
                forgeLevel.put(eq.id(), lvl);
            }
        }

        // ---------- 顺序（裁决 #528，2026-10-01）：主城升级排在建造之前 ----------
        // #527 量出：建造优先会让主城第 1 天到 4 级后 **44 天不动**（五座建筑每轮先吃木石）。
        // 主城优先的理由：① 主城等级是 `requireMainLevel` 的门槛（barracks 3 / academy 等），
        //   **主城优先才自洽**；② 与 B00「战力才是目的」一致；③ 现状是死亡螺旋。
        // ---------- 第五维：建造吃粮（#521）----------
        // 粮**不止造兵一个出口**：`building` 表里五条建筑 costBaseGrain > 0
        // （stable 200 / drill_ground 100 / hospital 300 / academy 300 / embassy 200），
        // 而模型里一条都没有 ⇒ 「粮溢出 237200」与铁那一轮一样是**缺维造成的**。
        // 本版按「**能升就升**」推进（与仓库/兵营同一口径），建造优先级按表顺序，
        // 造价按 `BUILDING_COST` 的 ratio^(n-1)（curve.base=0 ⇒ 用 costBase 直接起步）。
        if (withCap && dimBuilds) {
            List<String> grainBuildings = List.of(
                    "hospital", "academy", "stable", "embassy", "drill_ground");
            for (String bid : grainBuildings) {
                if (!buildsGate.contains(bid)) {
                    continue;                    // #543：先量「一座不建」的影响面
                }
                BuildingCfg row = null;
                for (var c : configs.all(BuildingCfg.class)) {
                    if (bid.equals(c.id())) {
                        row = c;
                        break;
                    }
                }
                if (row == null || row.costBaseGrain() <= 0L) {
                    continue;
                }
                long reqMain = (long) row.requireMainLevel();
                if (level < reqMain) {
                    continue;                    // 主城等级不够（与真实建造前置一致）
                }
                long lv = buildLevel.getOrDefault(bid, 0L);
                long maxLv = row.maxLevel();
                while (lv < maxLv) {
                    double ratio = Math.pow(BUILD_COST_RATIO, lv);
                    long needGrain = Math.round(row.costBaseGrain() * ratio);
                    long needWood = Math.round(row.costBaseWood() * ratio);
                    long needStone = Math.round(row.costBaseStone() * ratio);
                    long needIron = Math.round(row.costBaseIron() * ratio);
                    if (grain < needGrain || wood < needWood || stone < needStone || iron < needIron) {
                        break;                    // 任何一种不够就停（真建造也是整体校验）
                    }
                    grain -= needGrain;
                    wood -= needWood;
                    ledger.spend("wood", "build-gable", needWood);
                    stone -= needStone;
                    ledger.spend("stone", "build-gable", needStone);
                    iron -= needIron;
                    buildGrain += needGrain;
                    buildWood += needWood;
                    buildStone += needStone;
                    buildIron += needIron;
                    lv++;
                }
                buildLevel.put(bid, lv);
            }
        }

        // ---------- 造兵吃粮（第三维，#510 指出的那个出口缺位）----------
            // 铁粮的**唯一消费线**：训练部队吃 trainCostIron / trainCostGrain
            // （unit 表：重步兵 T1 = 铁 30 / 粮 20，四个兵种都是这个量级）。
            // 之前两版模型里铁粮只进仓库、只被 cap 截断，于是第 3 天起永久溢出 ——
            // 那正是「产出没有出口」的机器形态。补上这一维才能回答：
            // **溢出是不是「玩家本该去造兵」的正常现象**。
            // 真实上限是**三张表联立**，不是我能在这里编的一个数：
            //   `TRAIN_QUEUE_SLOTS = 1`（B05 §二：兵营按队列训练，一次一个槽位）
            //   人口上限 = Σ武将统帅值 + 科技加成（B05 §二 的「统帅上限」）
            //   单队上限 = `TROOP_PER_COMMAND = 5`
            // **本版只建模「每槽位一批、每批 100 个 T1」**（B11 ArmyState 的 load=20 ⇒ 5 队 × 20），
            // **人口那一维（Σ武将统帅值）没有建模** —— 没有武将存档，而编一个「一个武将 20 统帅」
            // 就是发明数值。所以造兵量在这一版里是**下界**，真实值只会更大。
            // 结论对下界稳健：连下界都吃不掉产出，真实值能不能吃满是另一件事（见 #511）。
            // 人口上限 = Σ 武将统帅值（B05 §二「人口/统帅上限 = Σ武将统帅值 + 科技加成」）。
            // **守方是真正的玩家，本该有武将存档 —— 而我没有。所以取下界**：科技加成必为 0，
            // 武将只按「一个都没有」算（0 个 ⇒ 人口 0 ⇒ 这一版造兵量为 0）。
            // ⚠️ 那样造兵这一维就恒为 0，等于白加。**改取 B00 §三 的默认口径**
            // （新号赠 1 名武将，hero 表 command 值域 46~100 ⇒ 取下界 46）
            // —— 「新号有什么」属产品口径，此处只把 46 标成**下界假设**并写进输出。
            // **--population 是探针旋钮，不是游戏数值**：它只在 CLI 里存在，默认 46 = hero 表 command 的最小值（1 名最弱武将 + 0 科技）。
            // 用途：量出「人口上限要多大，造兵才吃得满产���」—— 那个数就是 B05 §二 要补的口径。
            // ⚠️ `--population` 是**队伍统帅值**（一名最弱武将的 command 下界，B00 §三 新号赠 1 名），
            // **不是带兵上限** —— 真值要过 `HeroCalculator.troopCap`（= 统帅值 × TROOP_PER_COMMAND），
            // 否则上限会少算 `TROOP_PER_COMMAND` 倍（46 被当成 46，而真值是 46 × 5 = 230）。收口清单 #644。
            final long commandValue = Long.parseLong(options.getOrDefault("population", "46"));
            final long batchSize = 100L;
            // `--train-slots` 同 `--population`：**只在 CLI 里存在的探针旋钮**，
            // 用来量「把 TRAIN_QUEUE_SLOTS 调大能吃掉多少溢出」。默认值 1 = 表里的值。
            final long slots = Long.parseLong(options.getOrDefault("train-slots", "1"));
            // ⚠️ **造价单价从 `unit` 表读**（#646）：原来写死「铁 30 / 粮 20」，那只是 T1 步兵的**现值** ——
            // `unit.json` 一改就漂移，而模拟器照常输出一整套读数（#592 capBase / #594 initCap 同族）。
            // 兵种默认 T1 步兵（`--train-unit` 可换），与原注释「每批 100 个 T1」一致。
            final String trainUnitId = options.getOrDefault("train-unit", "unit_infantry_t1");
            final com.ironoath.config.cfg.UnitCfg trainUnit =
                    configs.get(com.ironoath.config.cfg.UnitCfg.class, trainUnitId);
            final long trainCostIronPerUnit = trainUnit.trainCostIron();
            final long trainCostGrainPerUnit = trainUnit.trainCostGrain();
            final com.ironoath.core.hero.HeroRules heroRules = new com.ironoath.core.hero.HeroRules(
                    configs.fixedParam("HERO_LEVEL_STEP"),
                    configs.fixedParam("HERO_STAR_STEP"),
                    configs.fixedParam("HERO_AWAKEN_STEP"),
                    (int) configs.longParam("HERO_STAR_MAX"),
                    (int) configs.longParam("HERO_SKILL_MAX_LEVEL"),
                    configs.longParam("HERO_ATTR_PER_PERCENT"),
                    configs.fixedParam("HERO_SUB_BONUS_RATIO"),
                    configs.fixedParam("HERO_ZONE_CAP"),
                    configs.fixedParam("HERO_BOND_BONUS"),
                    configs.longParam("TROOP_PER_COMMAND"),
                    (int) configs.longParam("LINEUP_HERO_COUNT"),
                    (int) configs.longParam("LINEUP_PRESET_COUNT"),
                    configs.curve("HERO_LEVEL_EXP").baseFixed(),
                    configs.curve("HERO_LEVEL_EXP").ratioFixed(),
                    configs.curve("HERO_GROWTH").exponentFixed());
            // **下界**：0 科技 + 1 名最弱武将。算法复用 core 的 troopCap，与线上同一份。
            final long population = com.ironoath.core.hero.HeroCalculator.troopCap(commandValue, heroRules);
            final long maxQueued = Math.min(slots * batchSize, population);
            if (withCap && dimTroops && barracksLevel >= 3 && population > 0L) {
                // 人口不足一批时**按人口切一批**，不是造满再截 ——
                // 那样会出现「在编 100 / 上限 46」这种自相矛盾的输出（真造兵也不会超人口）。
                // 上一批已派走：队列位空出来了（`perBatch` 本身就是「已派走」的证据，
                // 所以直接归零而不是记一个行军状态 —— 本版不建模行军去向）
                standing = 0L;
                long perBatch = Math.min(batchSize, maxQueued);
                // **每天补兵**：真实的 `ResourceSettlement` 每次结算都会把上一批派去行军/驻防，
                // 所以队列位会**空出来再被填满** —— 人口决定的是「每批能造多少」，
                // **不是「一生只能造多少」**。第一版每天只在 `standing + perBatch <= maxQueued`
                // 时造，等价于「造满就永远停手」，那是把队列当成了总量上限。
                while (standing + perBatch <= maxQueued) {
                    // ⚠️ **造价改成从 `unit` 表读，且去掉整除**（收口清单 #646）。原来两行是：
                    //     long costIron  = (perBatch / 100L) * 30L;
                    //     long costGrain = (perBatch / 100L) * 20L;
                    // 两个问题：① **30/20 是写死的** —— 恰���等于 T1 步兵现值，所以读数「看起来对」，
                    //   而 `unit.json` 一改就漂移（与 #592 capBase、#594 initCap、#641 科技加成同族：
                    //   量具与表脱钩时它照常输出一整套读数，全是按旧值算的）；
                    // ② **`perBatch / 100L` 是整除** —— 人口不足一批时（`maxQueued < 100`）
                    //   `perBatch / 100 == 0` ⇒ **造价算成 0，等于白造兵**。实测 `--population` 调到
                    //   小值时就会走到这一支，#644 把它暴露了出来。
                    // ⇒ 修法：单价从 `UnitCfg` 读（`trainCostIron` / `trainCostGrain`），
                    //   总量 = 单价 × 本批个数，**不整除**。
                    final long costIron = perBatch * trainCostIronPerUnit;
                    final long costGrain = perBatch * trainCostGrainPerUnit;
                    if (iron < costIron || grain < costGrain) {
                        break;
                    }
                    iron -= costIron;
                    grain -= costGrain;
                    standing += perBatch;
                    troopsMade += perBatch;
                }
            }
            if (withProducers && !cityFirst) {
                // ⚠️ **这一支原先是「数组顺序 + 第一个能升的就 break」（#684/#685）** ——
                // 那正是 #548 点名要消除的「先到先赢」，而 #649 只改了 city 那一支。
                // ⇒ 现在改成与 city 支同构的 `bestP` 选座，**比较量由 `--seat-by` 决定**：
                //   `rate`（默认）= 当前产量最低者先升（与 city 支同口径）
                //   `level` = 等级最低者先升（拉平**等级**而不是产量）
                //   `index` = 原样保留数组顺序（**对照组**：保留旧行为，`--seat-by=index` 即旧口径）
                // ⚠️ 为什么默认值是 `rate` 而不是 `index`：默认档 `--priority city` 走的就是
                // 「产量最低的先升」（#648/#649 已裁决），若 balanced 支默认用 `index`，
                // 同名档位的两支口径就不一致；统一成 `rate` 后 `--priority` 只决定
                // 「先升主城还是先升产出建筑」，不再顺带改变选座口径。
                // ⚠️ **另一处差异（本格发现，暂不动）**：这一支是 `for (p=0..3)` 逐座尝试、
                // 每天**可能升多座**，而 city 支每天只升一座 ⇒ #549「每轮只升一座」的裁决
                // 也只覆盖了 city 支。要不要给这一支也加「每天最多升 N 座」是产品口径，
                // 需先看 `--seat-by` 三档的读数差多少 ⇒ 下一格用读数去问，本格不加。
                int seatBestP = -1;
                long seatBestVal = Long.MAX_VALUE;
                for (int p = 0; p < producers.length; p++) {
                    // ⚠️ 等级上限守卫（#673）
                    if (producerLevels[p] >= producers[p][5]) {
                        continue;
                    }
                    long cWoodW = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                    long cStoneW = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                    long cIronW = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                    if (level < producers[p][0] || wood < cWoodW || stone < cStoneW || iron < cIronW) {
                        continue;   // 买不起就不参与选座（不是 break —— 见下）
                    }
                    // 比较量：`index` 口径下**不比较**，让循环顺序决定（等价于旧的先到先赢）
                    if (!"index".equals(seatBy)) {
                        long v = "level".equals(seatBy) ? producerLevels[p] : producerRates[p];
                        if (v < seatBestVal) {
                            seatBestVal = v;
                            seatBestP = p;
                        }
                    }
                }
                if ("index".equals(seatBy)) {
                    for (int p = 0; p < producers.length; p++) {
                        if (producerLevels[p] >= producers[p][5]) {
                            continue;
                        }
                        long cWoodI = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                        long cStoneI = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                        long cIronI = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                        if (level >= producers[p][0] && wood >= cWoodI && stone >= cStoneI && iron >= cIronI) {
                            seatBestP = p;   // 数组顺序第一个能升的（旧的先到先赢）
                            break;
                        }
                    }
                } else if (seatBestP < 0) {
                    // 没有任何一座买得起 —— 与旧口径的 `break` 同义（这一支当日不升）
                    seatBestP = -1;
                }
                for (int p = 0; p < producers.length; p++) {
                    if (p != seatBestP) {
                        continue;
                    }
                    // **每轮只升一级**：升到升不动会把当天全部资源吃掉、主城直接饿死
                    // （那是第一版 balanced 的 3 级的成因，已修）。
                    for (int step = 0; step < 1; step++) {
                        // ⚠️ 等级上限守卫（#673）：原来只判「每轮一级」与资源够不够，
                        // 不判 maxLevel ⇒ 产出建筑能无限升（实测粮建筑到 51 级）。
                        if (producerLevels[p] >= producers[p][5]) {
                            continue;
                        }
                        long out = producers[p][1];
                        long cWood = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                        long cStone = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                        long cIron = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                        if (level < producers[p][0] || wood < cWood || stone < cStone || iron < cIron) {
                            break;
                        }
                        wood -= cWood;
                        ledger.spend("wood", "gather-A", cWood);
                        stone -= cStone;
                        ledger.spend("stone", "gather-A", cStone);
                        buildWood += cWood;
                        buildStone += cStone;
                        iron -= cIron;
                        producerLevels[p]++;
                        // **产出随等级线性增长**：P(n) = base × n^exponent，exponent 取自 curve 表
                        // BUILDING_OUTPUT（现值 1）。第一版这里写的是 base × 1^(n-1) = base，
                        // 也就是「升了不涨产出」—— 那是我抄错了公式，于是「交替」那档的 3 级
                        // 完全是这个 bug 的产物，不是玩法结论。
                        producerRates[p] = Math.round(out * Math.pow(producerLevels[p], outExponent));
                    }
                }
            }
            // 一天一个循环：先按当天可花的钱升级，升级不了就把钱留到第二天（结余照常累积）
            while (true) {
                // ⚠️ **补上主城等级上限守卫（#670）**：原来这个 `while (true)` 只判资源够不够，
                // **不判等级上限** ⇒ 主城能无限升；而线上 `CityAppService:499` 是把
                // `(int) cfg.maxLevel()` 传给升级调用来封顶的 ⇒ 模拟器与线上不一致（第九处脱钩，
                // 与 #592 capBase、#665 仓容公式同族：表里那个 `maxLevel` 根本没被读到）。
                // ⚠️ 它对当前读数**无影响**（600 天只到 24 级，远未触顶），
                // 但**模拟器不得比线上更宽松**，否则一旦产出被放大就会算出线上不可能的等级。
                if (level >= mainCityCfg.maxLevel()) {
                    break;
                }
                // 主城造价也从表读（#598）：原来写死 1000，且下一行 stoneCost = woodCost
                // 隐含了「木石同价」这个假设 —— 表里确实是 1000/1000，但那是巧合不是约束。
                long woodCost = Math.round(mainCityCfg.costBaseWood() * Math.pow(costRatio, level - 1));
                long stoneCost = Math.round(mainCityCfg.costBaseStone() * Math.pow(costRatio, level - 1));
                if (wood < woodCost || stone < stoneCost) {
                    break;
                }
                wood -= woodCost;
                ledger.spend("wood", "city-upgrade", woodCost);
                stone -= stoneCost;
                ledger.spend("stone", "city-upgrade", stoneCost);
                cityWood += woodCost;      // #532 对账
                cityStone += stoneCost;
                level++;
                upgraded++;
            }
            if (withProducers && cityFirst) {
                // ⚠️ **选座口径 2026-10-02 裁决：按「产量最低的先升」**（#648/#649）。
                // 原来这里是 `for (p=0..3) { while(true){ if (pickedOnceThisRound) break; … } }`，
                // 而 `pickedOnceThisRound` 每天重置为 false（L753）⇒ 每天第一座把标志置位、
                // `p=1,2,3` 立刻 break ⇒ **每天只升第 0 座**（实测 `producerLevels=[21,0,0,0]`）。
                // ⚠️ 而同段注释写的是「木石按『谁缺谁先升』交替 —— #548 定位到那处卡点正是
                // 『数组顺序先到先赢』」⇒ **代码里根本没有「谁缺谁」的比较逻辑**，
                // 数组顺序（伐木场排第一）**就是** #548 点名要消除的那个「先到先赢」。
                //
                // **保留下来的部分**（#549 裁决「每轮只升一级」不动）：每天仍然只升一座。
                // **改掉的只有「选哪一座」**：从「数组顺序第一个」改成「当前产量最低的那座」。
                // 之所以能这样改：`producerRates` 初值是 `{0,0,0,0}`，而 1 级时
                // `Math.round(out × 1^exponent) = out` ⇒ **没升过的座产量恒为 0、必然最低**，
                // 所以四座会依次被拉起来；拉平之后再比真实产量（铁矿场底产 60 最低，会继续先升）
                // ⇒ **这正好解掉 #647 那条「铁 120 天只到 2030、买不起第一批兵」的链**。
                //
                // ⚠️ **平局处理**：产量完全相同（例如四座都没升）时按**索引小者优先**，
                // 那只是保证结果**确定**（同一天跑两次读数一致），不是策略口径。
                // ⚠️ **不变式**：`--priority balanced` 分支（L957 那段）**一行未动**，
                // `--cap=false` 也未动 ⇒ 不开该模式的路径与改动前完全一致。
                int bestP = -1;
                long bestRate = Long.MAX_VALUE;
                for (int p = 0; p < producers.length; p++) {
                    if (level < producers[p][0]) {
                        continue;
                    }
                    long cWood = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                    long cStone = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                    long cIron = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                    if (producerLevels[p] >= producers[p][5]) {
                        continue;   // 等级上限守卫（#673，与 gather-A 那处同口径）
                    }
                    if (wood < cWood || stone < cStone || iron < cIron) {
                        continue;
                    }
                    // 严格小于 ⇒ 平局时保留先到的（小索引），保证确定性
                    if (producerRates[p] < bestRate) {
                        bestRate = producerRates[p];
                        bestP = p;
                    }
                }
                if (bestP >= 0) {
                    int p = bestP;
                    long cWood = Math.round(producers[p][2] * Math.pow(costRatio, producerLevels[p]));
                    long cStone = Math.round(producers[p][3] * Math.pow(costRatio, producerLevels[p]));
                    long cIron = Math.round(producers[p][4] * Math.pow(costRatio, producerLevels[p]));
                    wood -= cWood;
                    ledger.spend("wood", "gather-B", cWood);
                    stone -= cStone;
                    ledger.spend("stone", "gather-B", cStone);
                    buildWood += cWood;
                    buildStone += cStone;
                    iron -= cIron;
                    producerLevels[p]++;
                    // 产出随等级按 curve 表 BUILDING_OUTPUT 增长（exponent 现值 1）
                    producerRates[p] = Math.round(producers[p][1]
                            * Math.pow(producerLevels[p], outExponent));
                }
            }
            // ⚠️ **产出建筑的产量汇总提到仓容分支之前（#672）**：
            // 原来这四行 `woodRate += withProducers ? producerRates[0] : 0L;`（四个资源）
            // 写在 `} else {` **里面** —— 而 `withCap` 默认 **true**（L641），
            // 走的是 `if` 支 ⇒ **汇总那段每天都不执行**
            // ⇒ **产出建筑（120 天到 23 级）的产量从未入账**，模拟器只报了 `resource` 表的底产。
            // 症状特别隐蔽：改动前把 `outExponent` 从 1.0 拧到 1.5，读数**逐位不变**（#671 留的待查项），
            // 因为那个指数只作用在 `producerRates` 上，而 `producerRates` 根本没进 `woodRate`。
            // 修法：汇总提到 `if (withCap)` **之前**，两支共用 —— `if` 支要截断、`else` 支不要，
            // 但「当天该产多少」这件事两支必须一致（线上 `ResourceRateService` 两者都算）。
            woodRate += withProducers ? producerRates[0] : 0L;
            stoneRate += withProducers ? producerRates[1] : 0L;
            ironRate += withProducers ? producerRates[2] : 0L;
            grainRate += withProducers ? producerRates[3] : 0L;
            // 当天的底产入账
            if (withCap) {
                long[] amounts = {wood, stone, iron, grain};
                for (int r = 0; r < 4; r++) {
                    long cap = initCap[r] + warehouseCapPer.applyAsLong(warehouseLevels[r]);
                    long produced = (r == 0 ? woodRate : r == 1 ? stoneRate : r == 2 ? ironRate : grainRate)
                            * 24L;
                    long room = Math.max(0L, cap - amounts[r]);
                    long gained = Math.min(room, produced);
                    overflow[r] += produced - gained;
                    amounts[r] += gained;
                }
                wood = amounts[0];
                stone = amounts[1];
                iron = amounts[2];
                grain = amounts[3];
            } else {
            wood += woodRate * 24L;
            stone += stoneRate * 24L;
            iron += ironRate * 24L;
            grain += grainRate * 24L;
            }

            if (day == days) {
            long producedWood = 5000L + woodRate * 24L * day;
            long producedStone = 5000L + stoneRate * 24L * day;
            System.out.println();
            System.out.println("=== 收支对账（#532）===");
            System.out.println("=== 每一处木的扣除（#534 自报；#541 起是真累计）===");
            ledger.tags.forEach((k, v) -> System.out.printf("  %-22s %d%n", k, v));
            long taggedTotal = ledger.tags.values().stream().mapToLong(Long::longValue).sum();
            System.out.printf("  **合计 %d（整 %d 天累计；应随天数单调增长）**%n",
                    taggedTotal, days);

            // 支出一律读**同一个 ledger**（#541：`buildWood` / `cityWood` 那些变量也是每轮重建的，
            // 用它们对账等于拿「当日」去减「累计」，差额必然不对）。
            long woodSpent = ledger.tags.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("wood/"))
                    .mapToLong(Map.Entry::getValue).sum();
            long stoneSpent = ledger.tags.entrySet().stream()
                    .filter(e -> e.getKey().startsWith("stone/"))
                    .mapToLong(Map.Entry::getValue).sum();
            // **必须减掉溢出**（#562）：`ResourceSettlement` 装满后
            // `overflow = output - room`，**产出被丢弃** —— 而这条公式原来不减它。
            // 45 天内木石从不溢出（溢出全在粮上，而对账只算木石）⇒ 缺陷一直藏着；
            // 600 天木石也满了（42000/42000）⇒ 对账立刻不平（+128 万）。
            // **这是对账工具的第三处缺陷**（前两处：×24 漏乘、拿当日当累计）。
            long woodGap = (5000L + woodRate * 24L * day) - woodSpent - wood - overflow[0];
            long stoneGap = (5000L + stoneRate * 24L * day) - stoneSpent - stone - overflow[1];
            System.out.printf("木：起始 5000 + 产出 %d - 扣除 %d = 结余 %d%n",
                    woodRate * 24L * day, woodSpent, wood);
            System.out.printf("石：起始 5000 + 产出 %d - 扣除 %d = 结余 %d%n",
                    stoneRate * 24L * day, stoneSpent, stone);
            System.out.printf("**对账差额：木 %+d / 石 %+d**（0 = 账平）%n", woodGap, stoneGap);
        }
        System.out.printf("%-6d%-10d%-12d%-12d%-12d%-12d%d%n",
                    day, level, wood, stone, iron, grain, upgraded);
            if (withCap) {
                System.out.printf("%-6s%-10s装备强化累计吃铁 %d（已开练 %d 件）%n",
                        "", "", forgeIron, forgePieces);
                // ⚠️ **门控关闭时必须说清「为什么是 0」**（#652）：`--builds-gate` 默认 `"none"`
                // （#543/#544 裁决：新号阶段那些建筑解锁的能力都用不上，而建造不可逆、先攒料升主城），
                // 关闭时 `buildLevel` 永远是空 Map —— 原来只打一个 `{}`，
                // 读者会以为这维坏了或数据丢了。现跑对照（45 天）：
                //   默认 none  ⇒ 12 级 / 铁 1456 / 建造累计吃粮 0（等级 {}）
                //   五座全开  ⇒  8 级 / 铁 2721 / 建造累计吃粮 32799
                //     （{stable=9, embassy=10, drill_ground=9, hospital=12, academy=9}）
                // ⇒ 这维是好的，0 是**配置选择**而不是缺陷。
                if (buildLevel.isEmpty() && "none".equals(options.getOrDefault("builds-gate", "none"))) {
                    System.out.printf("%-6s%-10s建造累计吃粮 0（**本档未开 --builds-gate**，默认 none，见 #543/#544 裁决："
                            + "那些建筑解锁的能力新号阶段都用不上，建造不可逆，先攒料升主城；"
                            + "传 --builds-gate=hospital,academy,stable,embassy,drill_ground 可开门对照）%n",
                            "", "");
                } else {
                    System.out.printf("%-6s%-10s建造累计吃粮 %d（等级 %s）%n",
                            "", "", buildGrain, buildLevel);
                }
                System.out.printf("%-6s%-10s建造/仓库累计吃：木 %d / 石 %d / 铁 %d / 粮 %d%n",
                        "", "", buildWood, buildStone, buildIron, buildGrain);
                System.out.printf("%-6s%-10s累计溢出（**产出被丢弃，不是排队**）：木 %d / 石 %d / 铁 %d / 粮 %d%n",
                        "", "", overflow[0], overflow[1], overflow[2], overflow[3]);
                // 统帅值与带兵上限**分开印**：这两个数混用正是 #644 的根，只印一个时读者无从判断它是哪个。
                // 兵种与单价也印出来：#646 之前它们是写死的 30/20，印出来才能自证「现在读的是表」。
                // ⚠️ **产出建筑等级必须进报告**（#649）：#647 那次「为什么只有伐木场升」只能靠临时
                // 插诊断才看到，而诊断行已经删了 —— 读数里缺这一维，下一次同样的问题还要重查一遍。
                System.out.printf("%-6s%-10s产出建筑等级 %s（⚠️索引 0=伐木场(wood)/1=采石场(stone)/**2=农场(grain)/3=铁矿场(iron)**，与 `producerIds = {lumber_camp, quarry, farm, iron_mine}` 同序（#683 更正：原写「0木/1石/2铁/3粮」把 2 与 3 说反了）%n",
                        "", "", java.util.Arrays.toString(producerLevels));
                System.out.printf("%-6s%-10s累计造兵 %d（在编 %d / 上限 %d = min(槽位×批次 %d, "
                                + "带兵上限 %d = 统帅值 %d × TROOP_PER_COMMAND %d)，兵营 %d 级，"
                                + "兵种 %s：铁 %d / 粮 %d 每兵，读自 unit 表）%n",
                        "", "", troopsMade, standing, maxQueued, slots * batchSize,
                        population, commandValue, heroRules.troopPerCommand(), barracksLevel,
                        trainUnitId, trainCostIronPerUnit, trainCostGrainPerUnit);
            }
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
        // **不再写死 3832**（#564）：那是 #491 时期「粮 76 640、无 cap」那一版的读数，
        // **口径变过之后已作废**（#542 账平 / #560 仓库容量修好 / #561 粮认定为设计后果）。
        // **当前口径下第 7 天的瓶颈是铁不是粮**：粮 30 000（cap 满）÷ 20/100 = 15 000 兵，
        // 而铁 2 050 ÷ 30/100 = **6 833 兵** ⇒ 铁先见底。
        // ⇒ 力比从 14 倍变成 **25 倍**，而结论方向不变（仍 100% 胜率、仍没有可失败的对立面）。
        // `--troops` 可覆盖；若口径再变，改这里的默认值时**必须重跑 `--f2p7d --days=7` 取结余**。
        int[] troop = {6833, 0, 0, 0};   // 零氪第 7 天能造的数量（铁是瓶颈，见上）
        int limit = Integer.parseInt(options.getOrDefault("troops", "6833"));   // #564
        troopsAll: {
            troop = new int[] {limit, 0, 0, 0};
            break troopsAll;
        }

        System.out.printf("=== B09 验收 4：零氪第 %d 天的军队打前三章（%d 局/关）%n", days, runs);
        System.out.printf("兵力：单兵种 T%d **%d**（资源结余 ÷ trainCost；#564：当前口径下**铁**是瓶颈）%n", tier, troop[0]);
        System.out.println("**这一格验的是「有没有可失败的对立面」**：零氪第 7 天 " + troop[0]
            + " 兵 vs 前三章最硬的一关 267 个 T2 敌人 ⇒ **力比 " + (troop[0] / 267)
            + " 倍**（#564：口径变过，力比从 14 升到 25）。");
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

    // ---------- 跨 JVM 确定性（B05 验收 3 缺的那一半） ----------

    /**
     * 同一个 seed 在**另一个 JVM 进程**里跑出逐位相同的结算摘要。
     *
     * <p><b>为什么需要它</b>：`BattleSimulatorTest` 只验了「同 seed 下触发序列完全一致」，
     * 那是**同一个 JVM 内重跑两次** —— 它证明代码没有随机源泄漏，但证明不了
     * 「换个 JVM 结果一样」。而这一条在 `验收矩阵` 里长期标 ✅ 却带着「同机器多 JVM 未跑」。
     *
     * <p><b>本模式输出的是可跨进程比对的摘要**（同一行固定格式），因此复跑命令是：
     * <pre>node tools/check-battle-determinism.mjs</pre>
     * 那份脚本跑两个 JVM 进程、比两行摘要 —— 断言在脚本里，**不在本方法内**
     * （单进程内跑两次证明不了「跨 JVM」，那正是 #505 那次要避免的自欺）。
     */
    private static boolean printDeterminism(BattleParamsResolver resolver, BattleRules rules,
                                            Map<UnitType, UnitStats> stats,
                                            Map<String, String> options, int tier) {
        int cases = Integer.parseInt(options.getOrDefault("cases", "5"));
        long seed = Long.parseLong(options.getOrDefault("seed", "20261001"));
        StringBuilder acc = new StringBuilder();
        for (int c = 0; c < cases; c++) {
            ArmySide atk = resolver.bareArmy("攻方",
                    Map.of(UnitType.INFANTRY, 900L + c * 37L, UnitType.CAVALRY, 700L,
                            UnitType.ARCHER, 600L, UnitType.SIEGE, 200L),
                    Long.MAX_VALUE / 4);
            ArmySide def = resolver.bareArmy("守方",
                    Map.of(UnitType.INFANTRY, 950L, UnitType.CAVALRY, 800L,
                            UnitType.ARCHER, 650L, UnitType.SIEGE, 150L),
                    Long.MAX_VALUE / 4);
            BattleResult r = BattleSimulator.simulate(new BattleInput(atk, def,
                    TerrainType.PLAIN, seed + c, BattleType.PVP_SOLO,
                    BattleModifier.none(), BattleModifier.none(), stats, rules,
                    DefenderStore.none()));
            // 摘要只取**逐位可复现**的量：胜负、回合数、总伤、总剩兵。
            // 不取耗时、不取任何 HashMap 的迭代顺序相关内容。
            // RoundSnapshot 的字段就是逐位可复现的量：每回合的攻守损失与技能触发。
            // 不取耗时、不取任何集合的迭代顺序相关内容（那会让摘要变成 JVM 版本的指纹）。
            long totalRounds = r.rounds().size();
            long atkLoss = r.rounds().stream().mapToLong(rd -> rd.atkLoss()).sum();
            long defLoss = r.rounds().stream().mapToLong(rd -> rd.defLoss()).sum();
            long skills = r.rounds().stream().mapToLong(rd -> rd.skills().size()).sum();
            acc.append(String.format("case%d winner=%s rounds=%d atkLoss=%d defLoss=%d skills=%d%n",
                    c, r.winner(), totalRounds, atkLoss, defLoss, skills));
        }
        String digest = acc.toString().trim();
        System.out.println("[determinism] cases=" + cases + " seed=" + seed
                + " jdk=" + System.getProperty("java.version"));
        System.out.println(digest);
        System.out.println("[determinism] 上面这一段就是比对对象；"
                + "另一个 JVM 进程跑同样的参数，输出必须逐字相同。");
        return true;
    }

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
