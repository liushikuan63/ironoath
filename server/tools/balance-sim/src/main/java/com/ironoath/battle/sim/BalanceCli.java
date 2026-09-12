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
import com.ironoath.config.ConfigRegistry;

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
 * <p>用法：
 * <pre>
 * # 单局可读战报
 * mvn -pl tools/balance-sim exec:java -Dexec.args="--single --atk 500,300,400,50 --def 450,350,380,60 --seed 20260906"
 *
 * # 四兵种两两对战胜率矩阵（B02 验收 4 / B05 验收 8 的数据来源）
 * mvn -pl tools/balance-sim exec:java -Dexec.args="--matrix --runs 1000 --tier 1 --size 1000"
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
        if (options.containsKey("matrix")) {
            int runs = Integer.parseInt(options.getOrDefault("runs", "1000"));
            long size = Long.parseLong(options.getOrDefault("size", "1000"));
            printMatrix(resolver, rules, stats, runs, size, tier);
        } else if (options.containsKey("single")) {
            long seed = Long.parseLong(options.getOrDefault("seed", "1"));
            printSingleBattle(resolver, rules, stats, options, seed);
        } else {
            System.err.println("用法：--single --atk 步,骑,弓,器 --def 步,骑,弓,器 --seed N");
            System.err.println("      --matrix --runs 1000 --tier 1 --size 1000");
            System.exit(2);
        }
        // 耗时是判断「能不能跑万局调平衡」的关键指标，每次都打出来
        System.out.printf("%n耗时 %.1f ms（内核纯 Java 无容器，这就是能跑万局的原因）%n",
                (System.nanoTime() - start) / 1_000_000.0d);
    }

    // ---------- 胜率矩阵 ----------

    private static void printMatrix(BattleParamsResolver resolver, BattleRules rules,
                                    Map<UnitType, UnitStats> stats, int runs, long size, int tier) {
        System.out.printf("=== 四兵种两两对战胜率矩阵（T%d，各 %d 兵，%d 局，不同 seed）===%n", tier, size, runs);
        System.out.println("读法：行是攻方，列是守方，格内是攻方胜率。");
        System.out.println("      B02 验收 4 要求 6 组对战全部落在 38%~62%；B05 验收 8 要求克制对与非克制对胜率差 >= 15%。");
        System.out.println();

        UnitType[] types = UnitType.values();
        Map<String, Double> rates = new LinkedHashMap<>();

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
                int wins = 0;
                int draws = 0;
                for (int i = 0; i < runs; i++) {
                    ArmySide atk = resolver.singleTypeArmy("atk", attacker, size, Long.MAX_VALUE / 4);
                    ArmySide def = resolver.singleTypeArmy("def", defender, size, Long.MAX_VALUE / 4);
                    BattleResult r = BattleSimulator.simulate(new BattleInput(
                            atk, def, TerrainType.PLAIN, 7_000_000L + i, BattleType.PVP_SOLO,
                            BattleModifier.none(), BattleModifier.none(), stats, rules, null));
                    if (r.winner() == Winner.ATTACKER) {
                        wins++;
                    } else if (r.winner() == Winner.DRAW) {
                        draws++;
                    }
                }
                // 平局算半场：只算胜场会让「双方都很肉打不完」的僵持局被记成守方全胜
                double rate = (wins + draws * 0.5d) / runs;
                rates.put(attacker.name() + ">" + defender.name(), rate);
                line.append(String.format("%9.1f%%", rate * 100));
            }
            System.out.println(line);
        }

        System.out.println();
        System.out.println("=== 验收判定 ===");
        int outOfRange = 0;
        for (Map.Entry<String, Double> e : rates.entrySet()) {
            boolean inRange = e.getValue() >= 0.38d && e.getValue() <= 0.62d;
            if (!inRange) {
                outOfRange++;
            }
            System.out.printf("  %-22s %5.1f%%  %s%n", e.getKey(), e.getValue() * 100,
                    inRange ? "OK（38%~62%）" : "✗ 超出 38%~62%");
        }
        System.out.printf("%n超出区间的对局：%d / %d%n", outOfRange, rates.size());
        if (outOfRange > 0) {
            System.out.println("说明：超出区间不一定是 bug —— 单向克制（如骑兵→弓兵）本就该有明显胜负差。");
            System.out.println("      B02 验收 4 的 38%~62% 针对的是「同兵力裸数值对局」，");
            System.out.println("      克制带来的偏移应由 unit 表的基础属性补偿，而不是靠削弱克制加成。");
        }
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

    /** 解析 "步,骑,弓,器" 形式的兵力描述。 */
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
