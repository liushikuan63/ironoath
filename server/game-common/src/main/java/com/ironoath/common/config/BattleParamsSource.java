package com.ironoath.common.config;

import java.util.Map;
import java.util.Set;

/**
 * 职责：战斗规则参数的读取端口 —— 「配置表 → 战斗内核」这条桥的抽象侧。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么要有这个端口</b>：战斗参数此前只有 {@code tools/balance-sim/BattleParamsResolver}
 * 一份实现，而 game-web 不依赖 tools、也不能依赖（game-web 被 spring-boot repackage
 * 成可执行 jar，不能当依赖用）。于是接战斗时最自然的做法是在 game-web 再写一份 ——
 * 那就有了两个家，而 balance-sim 正是<b>调数值用的 CLI</b>：
 * 两边分叉意味着「调好的数值」与「线上跑的数值」不是同一套，
 * 而表现是平衡矩阵怎么看都正常、线上却完全不是那个手感。这比一般的代码重复危险得多。
 *
 * <p>放在 game-common 与 {@link CurveSource}、{@link GlobalParamSource} 同一层，
 * 是沿用本项目既有的依赖倒置形状：端口在 common，实现方（game-config 的 BattleParams）
 * 与消费方（game-battle 的 {@code BattleRules.from}）各自只依赖 common，互不依赖。
 *
 * <p><b>方法名与 {@code BattleRules} 的字段名逐一对应</b>，这样映射就是一行一个的直译，
 * 不需要任何人在中间做「这个参数对应那个字段」的判断 —— 那种判断正是口径分叉的起点。
 *
 * <p>克制矩阵用<b>兵种名字符串</b>而不是 {@code UnitType}：UnitType 在 game-battle 里，
 * 而 game-config 不能依赖 game-battle（配置层依赖战斗内核是反的方向）。
 * 字符串到枚举的翻译由 {@code BattleRules.from} 做一次，且对未知名字报错。
 */
public interface BattleParamsSource {

    /** 最大回合数。来源 global.BATTLE_MAX_ROUNDS */
    int maxRounds();

    /** 防御软系数 K（定点）。来源 global.LANCHESTER_K */
    long lanchesterK();

    /** 单位生命折算进有效防御的权重（定点）。来源 global.HP_DEFENSE_WEIGHT */
    long hpDefenseWeightFixed();

    /** 损失随机浮动下界（定点）。来源 global.BATTLE_JITTER_MIN */
    long jitterMinFixed();

    /** 损失随机浮动上界（定点）。来源 global.BATTLE_JITTER_MAX */
    long jitterMaxFixed();

    /** 前排损失分摊（定点）。来源 global.COUNTER_ADVANCE_FRONT */
    long rowFrontFixed();

    /** 中排损失分摊（定点）。来源 global.COUNTER_ADVANCE_MID */
    long rowMidFixed();

    /** 后排损失分摊（定点）。来源 global.COUNTER_ADVANCE_BACK */
    long rowBackFixed();

    /** 克制加成（定点）。来源 global.COUNTER_BONUS */
    long counterBonusFixed();

    /** 被克减益（定点）。来源 global.COUNTER_PENALTY */
    long counterPenaltyFixed();

    /** 平局判定的剩余兵力差距阈值（定点）。来源 global.BATTLE_DRAW_GAP_RATIO */
    long drawGapRatioFixed();

    /** PVE 死亡比例（定点）。来源 global.WOUND_RATIO_PVE_DEAD */
    long pveDeadRatioFixed();

    /** PVP 攻方死亡比例（定点）。来源 global.WOUND_RATIO_PVP_ATTACKER_DEAD */
    long pvpAttackerDeadRatioFixed();

    /** PVP 守方死亡比例（定点）。来源 global.WOUND_RATIO_PVP_DEFENDER_DEAD */
    long pvpDefenderDeadRatioFixed();

    /**
     * 克制矩阵：兵种名 → 被它克制的兵种名集合。来源 unit_counter 表。
     *
     * <p><b>四个兵种必须都在键里</b>（没有克制关系的给空集合）：
     * 缺键在 {@code BattleRules.counters} 里会被当成「不克制」，
     * 但任何直接遍历这个 map 的代码都会漏掉那个兵种 —— 而漏掉的表现是
     * 「这个兵种永远不触发克制」，不报错，只在胜率矩阵上看起来偏弱。
     */
    Map<String, Set<String>> counterMatrix();
}
