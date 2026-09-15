package com.ironoath.battle.sim;

import com.ironoath.battle.ArmySide;
import com.ironoath.battle.BattleInput;
import com.ironoath.battle.BattleModifier;
import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleRules;
import com.ironoath.battle.BattleSimulator;
import com.ironoath.battle.BattleType;
import com.ironoath.battle.TerrainType;
import com.ironoath.battle.UnitStats;
import com.ironoath.battle.UnitType;
import com.ironoath.battle.Winner;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.UnitCounterCfg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 职责：四兵种两两对战胜率矩阵的<b>判定</b>（B02 验收 4 + B05 验收 8）。
 * 依赖：game-config（读 unit_counter / unit）、game-battle（内核）。
 *
 * <p><b>为什么判定值得单独一个类</b>：CLI 原先只把 12 个方向的胜率打出来、
 * 附一句"超出区间不一定是 bug"，于是"矩阵是否合格"要靠人读数字 ——
 * 而验收矩阵里 B02 验收 4 的状态就这么一直停在"🟡 需人工"。
 * 判定做成这里的一等公民之后，CLI 的退出码与 JUnit 用例用的是同一份口径。
 *
 * <p><b>口径不是本类发明的</b>，来自 {@code unit_counter.json} 的 designNote（两条验收标准的冲突裁定）：
 * <ul>
 *   <li><b>对称对</b>（相互克制，如步↔骑、步↔弓；或无克制关系，如步 vs 器、弓 vs 器）：
 *       双向胜率都必须在 38%~62%（B02 验收 4）。相互克制时双方同时拿到 +25% 与 -20%，
 *       净倍率 1.0 完全抵消，胜负由裸数值决定 —— 这四组测的就是"裸数值是否拉平"。</li>
 *   <li><b>单向克制对</b>（骑兵→弓兵、骑兵→攻城器）：期望结果本就是一边倒
 *       （净倍率 1.5625 在 8 回合复利下产生约 21% 的剩余兵力差），由 B05 验收 8 管辖：
 *       克制方向必须领先反向至少 15%。把它们也塞进 38%~62% 等于要求削弱克制加成，
 *       而 B05 验收 8 明确要求非对称 —— 两条验收标准在这两组上直接冲突，
 *       designNote 的裁定是"各管各的"，本类照此实现。</li>
 * </ul>
 *
 * <p><b>"哪几对是单向的"从 unit_counter 表推导，不写死名单</b>：
 * 硬编码两组名字的话，将来表里新增/删除一条克制关系，矩阵会按错误的口径判定
 * （把单向对按对称判、或反过来），而且不会有任何编译期信号。
 * 推导规则：一行 = 一条有向克制关系；A→B 有行、B→A 无行 ⇒ 单向；两边都有 ⇒ 相互；都没有 ⇒ 无关系。
 * 后两类都归"对称对"（都要求双向落区间）。WALL / TRAP 相关的行是攻城器对建筑，
 * 不属于兵种两两，构造对时自动被 UnitType 过滤掉。
 */
public final class BalanceMatrix {

    /** 对称对的胜率区间：B02 验收 4 的 38%~62%。 */
    public static final double SYMMETRIC_MIN = 0.38d;
    public static final double SYMMETRIC_MAX = 0.62d;

    /** 单向克制对的最小领先幅度：B05 验收 8 的"胜率差 >= 15%"。 */
    public static final double ONE_WAY_MIN_GAP = 0.15d;

    /**
     * 矩阵用的固定 seed 基数。与最初 CLI 一致（7_000_000 + i）：
     * 判定结果必须可复现，换一批 seed 会得到另一组数字，而"验收"要的是同一批数字的可重复性。
     */
    private static final long SEED_BASE = 7_000_000L;

    /** 一个方向的实测胜率。平局算半场（见 {@link #winRate}）。 */
    public record Cell(UnitType attacker, UnitType defender, double winRate) {
    }

    /**
     * 一对兵种的两个方向。
     *
     * @param favored 单向克制时是拥有克制加成的一方；对称对为 null
     */
    public record Pair(UnitType a, UnitType b, double rateAToB, double rateBToA, UnitType favored) {
        public boolean oneWay() {
            return favored != null;
        }
    }

    /**
     * @param cells      全部 12 个方向（不含对角）
     * @param pairs      6 个无序对
     * @param violations 违规明细；为空表示两条验收标准都满足
     */
    public record Outcome(List<Cell> cells, List<Pair> pairs, List<String> violations) {
        public boolean passed() {
            return violations.isEmpty();
        }
    }

    private BalanceMatrix() {
    }

    /**
     * 从 unit_counter 表推导单向克制对，元素形如 {@code "CAVALRY>ARCHER"}（含方向）。
     *
     * @throws IllegalStateException 表在四兵种内出现"A→B 与 B→A 都缺"以外的异常形状时不会触发；
     *                               本方法对任何表内容都返回良定义的结果（空集也是合法输入）
     */
    public static Set<String> oneWayPairs(ConfigRegistry configs) {
        Set<String> relations = new HashSet<>();
        for (UnitCounterCfg row : configs.all(UnitCounterCfg.class)) {
            relations.add(row.attacker().name() + ">" + row.defender().name());
        }
        Set<String> oneWay = new HashSet<>();
        UnitType[] types = UnitType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                boolean iToJ = relations.contains(types[i].name() + ">" + types[j].name());
                boolean jToI = relations.contains(types[j].name() + ">" + types[i].name());
                if (iToJ && !jToI) {
                    oneWay.add(types[i].name() + ">" + types[j].name());
                } else if (jToI && !iToJ) {
                    oneWay.add(types[j].name() + ">" + types[i].name());
                }
            }
        }
        return oneWay;
    }

    /** 跑完整矩阵并按口径判定。每对两个方向各 {@code runs} 局、不同 seed，各 {@code size} 兵。 */
    public static Outcome run(BattleParamsResolver resolver, BattleRules rules,
                              Map<UnitType, UnitStats> stats, Set<String> oneWay,
                              int runs, long size) {
        List<Pair> pairs = new ArrayList<>();
        List<Cell> cells = new ArrayList<>();
        UnitType[] types = UnitType.values();
        for (int i = 0; i < types.length; i++) {
            for (int j = i + 1; j < types.length; j++) {
                UnitType a = types[i];
                UnitType b = types[j];
                double rateAToB = winRate(resolver, rules, stats, a, b, runs, size);
                double rateBToA = winRate(resolver, rules, stats, b, a, runs, size);
                cells.add(new Cell(a, b, rateAToB));
                cells.add(new Cell(b, a, rateBToA));
                UnitType favored = oneWay.contains(a.name() + ">" + b.name()) ? a
                        : oneWay.contains(b.name() + ">" + a.name()) ? b : null;
                pairs.add(new Pair(a, b, rateAToB, rateBToA, favored));
            }
        }
        return new Outcome(cells, pairs, judge(pairs));
    }

    /**
     * 按口径判定。**纯函数**：只吃实测胜率，便于用合成数据单测判定器本身
     * （不必为了验一条规则去改真表或被测源码）。
     */
    static List<String> judge(List<Pair> pairs) {
        List<String> violations = new ArrayList<>();
        for (Pair pair : pairs) {
            if (pair.oneWay()) {
                double favored = pair.favored() == pair.a() ? pair.rateAToB() : pair.rateBToA();
                double other = pair.favored() == pair.a() ? pair.rateBToA() : pair.rateAToB();
                double gap = favored - other;
                if (gap < ONE_WAY_MIN_GAP) {
                    violations.add(String.format(
                            "单向克制对 %s>%s 领先 %.1f%%，不足 %.0f%%（B05 验收 8）：克制方 %.1f%% vs 反向 %.1f%%",
                            pair.favored().name(), (pair.favored() == pair.a() ? pair.b() : pair.a()).name(),
                            gap * 100, ONE_WAY_MIN_GAP * 100, favored * 100, other * 100));
                }
                continue;
            }
            checkSymmetric(pair.a(), pair.b(), pair.rateAToB(), violations);
            checkSymmetric(pair.b(), pair.a(), pair.rateBToA(), violations);
        }
        return violations;
    }

    private static void checkSymmetric(UnitType attacker, UnitType defender, double rate,
                                       List<String> violations) {
        if (rate < SYMMETRIC_MIN || rate > SYMMETRIC_MAX) {
            violations.add(String.format(
                    "对称对 %s>%s 胜率 %.1f%% 超出 %.0f%%~%.0f%%（B02 验收 4）",
                    attacker.name(), defender.name(), rate * 100,
                    SYMMETRIC_MIN * 100, SYMMETRIC_MAX * 100));
        }
    }

    /**
     * 单一兵种对阵若干局的攻方胜率。
     *
     * <p>平局算半场：只算胜场会让"双方都很肉打不完"的僵持局被记成守方全胜 ——
     * 那是把"没打完"与"打输了"混成同一件事。
     */
    private static double winRate(BattleParamsResolver resolver, BattleRules rules,
                                  Map<UnitType, UnitStats> stats,
                                  UnitType attacker, UnitType defender, int runs, long size) {
        int wins = 0;
        int draws = 0;
        for (int i = 0; i < runs; i++) {
            ArmySide atk = resolver.singleTypeArmy("atk", attacker, size, Long.MAX_VALUE / 4);
            ArmySide def = resolver.singleTypeArmy("def", defender, size, Long.MAX_VALUE / 4);
            BattleResult r = BattleSimulator.simulate(new BattleInput(
                    atk, def, TerrainType.PLAIN, SEED_BASE + i, BattleType.PVP_SOLO,
                    BattleModifier.none(), BattleModifier.none(), stats, rules, null));
            if (r.winner() == Winner.ATTACKER) {
                wins++;
            } else if (r.winner() == Winner.DRAW) {
                draws++;
            }
        }
        return (wins + draws * 0.5d) / runs;
    }

    /** 便于打印：十二个方向按"攻方>守方"索引。 */
    public static Map<String, Double> rateByDirection(Outcome outcome) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Cell cell : outcome.cells()) {
            out.put(cell.attacker().name() + ">" + cell.defender().name(), cell.winRate());
        }
        return out;
    }
}
