package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 职责：战斗内核 —— 改良兰彻斯特 8 回合结算，静态纯函数。
 * 依赖：game-common 的 FixedPoint 与 Rng（纯 Java，零框架）。
 *
 * <h2>五条硬约束（B05 §1.2，违反即视为任务失败）</h2>
 * <ol>
 *   <li><b>纯函数</b>：相同 input 必定产生逐字段相等的 output，无任何隐藏状态</li>
 *   <li><b>零框架依赖</b>：不 import 任何 Spring / 数据库 / 网络类（scripts/check-layering.sh 强制）</li>
 *   <li><b>定点数</b>：全程 long（×10000），无 double / float</li>
 *   <li><b>无 IO、无日志副作用、无 System.currentTimeMillis()</b>：时间不进入结算</li>
 *   <li><b>线程安全</b>：无静态可变状态，可多线程并发跑批量模拟做平衡验证</li>
 * </ol>
 *
 * <h2>确定性是怎么保证的（B05 §1.7）</h2>
 * <ul>
 *   <li>兵种容器一律 EnumMap，按 {@link UnitType#values()} 声明顺序遍历。
 *       HashMap 的迭代顺序依赖 hash 与桶容量；求和满足交换律所以看不出问题，
 *       但损失分摊要决定「先扣哪个兵种」，顺序不同结果就不同 —— 同 seed 的战报将无法复现</li>
 *   <li>每个随机点走 {@code rng.fork(salt)} 独立子流，salt 由 (回合, 阶段, 站位, 技能序号, 阵营) 决定。
 *       这样<b>新增一个随机调用点不会移动已有的随机序列</b>；否则改一行代码就会让全部历史战报失效</li>
 *   <li>不用并行流。批量模拟在外层并行（每局独立 seed），单局内部严格顺序执行</li>
 * </ul>
 *
 * <h2>⚠️ 一个必须与策划确认的模型缺口</h2>
 * B00 与 B05 给出的减员公式是「损失 = 总兵数 × 减员系数 × 随机浮动」，
 * 其中<b>单位生命（hp）不出现在任何一项里</b>。本实现严格按公式落地，因此
 * {@code unit.json} 的 hp 字段目前<b>不影响战斗结果</b> ——
 * T5 攻城器（hp 126）与 T1 攻城器（hp 80）在同等数量下每回合损失的数量完全相同。
 *
 * <p>这是真实的设计缺陷而不是实现偷懒：hp 是玩家能看见的属性，
 * 「升了阶级血量涨了但打起来没区别」会被直接感知为数值造假。但没有擅自改公式，
 * 因为两种改法后果完全不同：把「总兵数」换成「总血量当量」会让 hp 生效但显著加强肉盾、
 * 连带改变兵种平衡矩阵；把 hp 折进有效防御改动最小，但 hp 与 defense 会变成同一属性的两个名字。
 * 两者都要重跑平衡矩阵才能定稿。
 * TODO(需确认): hp 如何进入减员公式。当前实现为「不进入」，严格遵循 B00 原文。
 */
public final class BattleSimulator {

    // ---------- fork salt ----------
    // 每个随机点一个独立 salt。salt 必须稳定：改了 salt 等于改了所有历史战报的结果。
    private static final long SALT_JITTER_ATTACKER = 1_000L;
    private static final long SALT_JITTER_DEFENDER = 1_001L;
    private static final long SALT_SKILL_BASE = 100_000L;
    private static final long SALT_SIDE_DEFENDER = 50_000L;
    private static final long SALT_PHASE_STEP = 1_000L;
    private static final long SALT_SKILL_INDEX_STEP = 10L;

    private static final int ROW_FRONT = 0;
    private static final int ROW_MID = 1;
    private static final int ROW_BACK = 2;

    private BattleSimulator() {
    }

    /**
     * 模拟一场战斗。
     *
     * @param input 战前快照 + seed + 规则参数。战报只需存 input 即可 100% 复算（铁律 4）
     * @return 完整战果，含逐回合快照，客户端可直接播放
     */
    public static BattleResult simulate(BattleInput input) {
        if (input == null) {
            throw new IllegalArgumentException("BattleInput 不得为 null");
        }
        BattleRules rules = input.rules();
        Rng battle = Rng.of(input.seed());

        SideState atk = new SideState(input.attacker(), true);
        SideState def = new SideState(input.defender(), false);

        // BOSS 机制（B09 §二）。三个量都按「原始守军」而不是「当前守军」计算：
        // 按当前算的话，机制会随着守军被打残而减弱，恰好在最需要它的时候失效
        BossMechanic mechanic = input.defenderMechanic();
        long defOriginalTotal = def.totalUnits();
        Map<UnitType, Long> defOriginalCounts = def.snapshotCounts();

        List<RoundSnapshot> rounds = new ArrayList<>(rules.maxRounds());
        List<Long> attritionLog = new ArrayList<>(rules.maxRounds());
        Winner winner = null;

        for (int round = 1; round <= rules.maxRounds(); round++) {
            Rng roundRng = battle.fork(round);
            List<SkillTrigger> triggers = new ArrayList<>();

            // ---- 阶段 0：BOSS 召唤援军。放在一切之前，本回合就能被打到 ----
            // 逼玩家速攻：拖得越久，守军越多，而 BATTLE_MAX_ROUNDS 是有限的，
            // 所以「慢慢磨」这条路会被机制本身堵死
            if (mechanic.summonsOn(round) && def.totalUnits() > 0L) {
                long summoned = FixedPoint.round(FixedPoint.mul(
                        FixedPoint.of(defOriginalTotal), mechanic.ratioFixed()));
                Map<UnitType, Long> reinforcement = splitByComposition(defOriginalCounts,
                        defOriginalTotal, summoned);
                def.reinforce(reinforcement);
                triggers.add(new SkillTrigger(round, SkillPhase.ROUND_START, BossMechanic.TRIGGER_SOURCE, 0,
                        "boss_reinforcement", SkillEffect.BUFF_DEF, mechanic.ratioFixed(), summoned));
            }

            // ---- 阶段 1：回合开始。顺序固定：攻方先、守方后，同方按 slot 升序（B05 §1.4）----
            atk.tickEffects();
            def.tickEffects();
            atk.castSkills(input, round, roundRng, SkillPhase.ROUND_START, def, triggers);
            def.castSkills(input, round, roundRng, SkillPhase.ROUND_START, atk, triggers);
            atk.castSkills(input, round, roundRng, SkillPhase.EVERY_ROUND, def, triggers);
            def.castSkills(input, round, roundRng, SkillPhase.EVERY_ROUND, atk, triggers);

            // ---- 阶段 2：攻击结算。双方都用回合开始时的兵力快照，避免先后手偏差 ----
            Map<UnitType, Long> atkCounts = atk.snapshotCounts();
            Map<UnitType, Long> defCounts = def.snapshotCounts();
            long atkTotal = ArmySide.totalOf(atkCounts);
            long defTotal = ArmySide.totalOf(defCounts);

            long atkAttack = effectiveAttack(input, atk, atkCounts, defCounts, defTotal, true);
            long defAttack = effectiveAttack(input, def, defCounts, atkCounts, atkTotal, false);
            long atkDefense = effectiveDefense(input, atk, atkCounts);
            long defDefense = effectiveDefense(input, def, defCounts);

            // ---- BOSS 防御姿态：剩余兵力跌破阈值后防御提升 ----
            // 逼玩家换兵种或在阈值之前打穿：一旦进入姿态，同样的输出效率会明显下降，
            // 而「换更高穿透的兵种」是免费的应对，「把练度堆到能硬穿」要花钱
            if (mechanic.type() == BossMechanic.Type.SHIELD_PHASE && defTotal > 0L) {
                long triggerAt = FixedPoint.round(FixedPoint.mul(
                        FixedPoint.of(defOriginalTotal), mechanic.thresholdFixed()));
                if (defTotal <= triggerAt) {
                    long before = defDefense;
                    defDefense = FixedPoint.mul(defDefense, FixedPoint.ONE + mechanic.bonusFixed());
                    if (defDefense != before) {
                        triggers.add(new SkillTrigger(round, SkillPhase.EVERY_ROUND, BossMechanic.TRIGGER_SOURCE, 0,
                                "boss_shield_phase", SkillEffect.BUFF_DEF, mechanic.bonusFixed(),
                                defDefense - before));
                    }
                }
            }

            long atkAttrition = attrition(atkAttack, defDefense, rules.lanchesterK());
            long defAttrition = attrition(defAttack, atkDefense, rules.lanchesterK());
            attritionLog.add(atkAttrition);

            // 损失 = 对方总兵数 × 己方减员系数 × 随机浮动
            long lossToDefender = lossOf(defTotal, atkAttrition,
                    jitter(roundRng, SALT_JITTER_ATTACKER, rules));
            long lossToAttacker = lossOf(atkTotal, defAttrition,
                    jitter(roundRng, SALT_JITTER_DEFENDER, rules));

            // ---- 阶段 3：受击结算（ON_HIT）。被击方判定，效果作用于本回合 ----
            def.castSkills(input, round, roundRng, SkillPhase.ON_HIT, atk, triggers);
            atk.castSkills(input, round, roundRng, SkillPhase.ON_HIT, def, triggers);
            // DAMAGE 技能的额外损失沿用同一减员模型：对方总兵数 × 己方减员系数 × 技能倍率。
            // 注意归属：atk 的额外伤害落在 def 身上，反之亦然
            lossToDefender += lossOf(defTotal, atkAttrition, atk.pendingExtraDamageRatio());
            lossToAttacker += lossOf(atkTotal, defAttrition, def.pendingExtraDamageRatio());

            // ---- BOSS 反弹：守方本回合所受损失的一部分原样落回攻方 ----
            // 逼玩家带治疗或减伤武将：反弹量与「你打了多少」成正比，
            // 所以输出越高反弹越痛，纯堆输出这条路会被机制反噬。
            // 用守方的实际损失（含技能额外伤害）作为基数，玩家才能从战报里反推出反弹来源
            if (mechanic.type() == BossMechanic.Type.COUNTER_STRIKE && lossToDefender > 0L) {
                long reflected = FixedPoint.round(FixedPoint.mul(
                        FixedPoint.of(lossToDefender), mechanic.ratioFixed()));
                if (reflected > 0L) {
                    lossToAttacker += reflected;
                    triggers.add(new SkillTrigger(round, SkillPhase.ON_HIT, BossMechanic.TRIGGER_SOURCE, 0,
                            "boss_counter_strike", SkillEffect.DAMAGE, mechanic.ratioFixed(), reflected));
                }
            }

            // ---- 阶段 4：落地损失（按前排 0.5 / 中排 0.3 / 后排 0.2 分摊）----
            Map<UnitType, Long> defLost = def.applyLoss(lossToDefender, rules);
            Map<UnitType, Long> atkLost = atk.applyLoss(lossToAttacker, rules);
            long grossDefLoss = sum(defLost);
            long grossAtkLoss = sum(atkLost);

            // ---- 阶段 5：回合结束（ON_DEATH）。有单位阵亡才判定 ----
            if (grossDefLoss > 0L) {
                def.castSkills(input, round, roundRng, SkillPhase.ON_DEATH, atk, triggers);
            }
            if (grossAtkLoss > 0L) {
                atk.castSkills(input, round, roundRng, SkillPhase.ON_DEATH, def, triggers);
            }
            long atkHealed = sum(atk.applyHeal());
            long defHealed = sum(def.applyHeal());

            // 快照记录净损失。治疗量来自本场累计损失池，理论上可能超过本回合损失，
            // 此时净值按 0 记（BattleResult 不接受负损失），治疗本身已反映在兵力表里
            rounds.add(new RoundSnapshot(round, atk.snapshotCounts(), def.snapshotCounts(),
                    Math.max(0L, grossAtkLoss - atkHealed),
                    Math.max(0L, grossDefLoss - defHealed),
                    atkAttack, defDefense, atkAttrition, triggers));

            if (def.totalUnits() <= 0L || atk.totalUnits() <= 0L) {
                winner = (def.totalUnits() <= 0L && atk.totalUnits() <= 0L) ? Winner.DRAW
                        : (def.totalUnits() <= 0L ? Winner.ATTACKER : Winner.DEFENDER);
                break;
            }
        }

        if (winner == null) {
            winner = judgeByRemainingRatio(atk, def, rules.drawGapRatioFixed());
        }

        Casualties atkCas = casualties(atk.totalLost(), atk.hospitalCapacity(),
                rules.deadRatio(input.battleType(), true));
        Casualties defCas = casualties(def.totalLost(), def.hospitalCapacity(),
                rules.deadRatio(input.battleType(), false));

        // 剩余负载只与攻方有关：掠夺能带走多少由攻方的运输能力决定，所以这里读攻方那份属性表
        long lootCapacity = remainingLoad(input.attacker().units(), atk.counts(),
                input.attackerUnitStats());
        Map<String, Long> loot = settleLoot(input, lootCapacity);

        return new BattleResult(winner, rounds, rounds.size(),
                atk.counts(), def.counts(),
                atkCas.dead(), atkCas.wounded(), atkCas.overflowDead(),
                defCas.dead(), defCas.wounded(), defCas.overflowDead(),
                loot, lootCapacity, input.seed(), attritionLog);
    }

    // ---------- 结算子过程 ----------

    /**
     * 损失 = 总兵数 × 减员系数 × 倍率，全程定点，最后一步才落地成整数兵数。
     *
     * <p>每回合损失比例完全由减员系数决定，而减员系数由 lanchesterK 调节：
     * 势均力敌时减员系数 = 1/(1+K)，K=7 即每回合损失约 12.5%。
     * 之所以调 K 而不是额外加一个标度乘法项：标度项会把单回合损失上限钉死，
     * 导致 400:1 的压倒性兵力也永远打不干净一小股守军；调 K 则同时保住了这两端。
     */
    /**
     * 把一个总量按参考构成拆成各兵种的数量，最大余数法保证 Σ 精确等于 total。
     *
     * <p>与 game-core 的 {@code TierSplit} 是同一条纪律（任何按比例分摊都必须断言总量守恒，
     * 否则缺口会静默消失），但这里不能复用它：game-battle 不依赖 game-core
     * （两者是兄弟模块），为一个 15 行的分摊引入一条模块依赖不值得。
     *
     * <p>顺序按 UnitType 声明序，余数并列时也按声明序 —— 增援的兵种构成必须可复现，
     * 否则同一个 seed 在两台机器上会召出不同的援军（铁律 4）。
     */
    private static Map<UnitType, Long> splitByComposition(Map<UnitType, Long> reference,
                                                          long referenceTotal, long total) {
        Map<UnitType, Long> out = new EnumMap<>(UnitType.class);
        if (total <= 0L || referenceTotal <= 0L) {
            return out;
        }
        long assigned = 0L;
        UnitType last = null;
        long[] remainders = new long[UnitType.values().length];
        int index = 0;
        for (UnitType type : UnitType.values()) {
            long count = reference.getOrDefault(type, 0L);
            if (count <= 0L) {
                index++;
                continue;
            }
            long share = count * total / referenceTotal;
            remainders[index] = count * total % referenceTotal;
            out.put(type, share);
            assigned += share;
            last = type;
            index++;
        }
        long missing = total - assigned;
        // 余数补给最后一个有兵种的项：它拿到的份额最大，再加一点也不会改变构成形状，
        // 而且这样只有一处需要处理，不必再排一次序
        if (missing > 0L && last != null) {
            out.put(last, out.get(last) + missing);
        }
        return out;
    }

    private static long lossOf(long totalUnits, long attritionFixed, long multiplierFixed) {
        if (totalUnits <= 0L || attritionFixed <= 0L || multiplierFixed <= 0L) {
            return 0L;
        }
        return FixedPoint.round(FixedPoint.mul(
                FixedPoint.mul(FixedPoint.of(totalUnits), attritionFixed), multiplierFixed));
    }

    /**
     * 有效攻击 = Σ(数量 × 单位攻击 × 各乘区 × 克制系数)。
     *
     * <p>克制系数按<b>对方兵种构成加权</b>：对方混编时，己方某兵种的克制倍率等于
     * 它对每个对方兵种的倍率按对方数量占比加权平均。这样「带弓兵打骑步混编」与
     * 「带弓兵打纯骑兵」结果不同，编队构成才有意义。
     */
    private static long effectiveAttack(BattleInput input, SideState side,
                                        Map<UnitType, Long> ownCounts,
                                        Map<UnitType, Long> enemyCounts, long enemyTotal,
                                        boolean isAttacker) {
        BattleRules rules = input.rules();
        ArmySide army = isAttacker ? input.attacker() : input.defender();
        BattleModifier modifier = isAttacker ? input.modifierAttacker() : input.modifierDefender();
        // 双方各读自己那份兵种属性表：共用一份等于把守方的阶级属性替换成攻方的加权平均值
        Map<UnitType, UnitStats> ownStats =
                isAttacker ? input.attackerUnitStats() : input.defenderUnitStats();
        long heroBonus = side.heroAttackBonus();
        boolean vsBuilding = appliesToBuilding(input);

        long total = 0L;
        for (UnitType type : UnitType.values()) {
            long count = ownCounts.get(type);
            if (count <= 0L) {
                continue;
            }
            UnitStats stats = ownStats.get(type);
            long counter = weightedCounter(type, enemyCounts, enemyTotal, rules);
            // 攻城器的对建筑倍率只在攻城战或城下地形生效：打野时不该让攻城器变强
            long siege = vsBuilding ? stats.vsBuildingBonusFixed() : 0L;
            AttackMultipliers multipliers = new AttackMultipliers(
                    heroBonus,
                    army.techBonus().attackFixed(),
                    army.equipBonusFixed(),
                    FixedPoint.mul(counter, FixedPoint.ONE + siege),
                    rules.terrainAttack(input.terrain()),
                    modifier.totalFixed() + side.attackBuff() - side.attackDebuff());
            long perUnit = FixedPoint.mul(stats.attackFixed(), multipliers.compose());
            total += FixedPoint.mul(FixedPoint.of(count), perUnit);
        }
        return total;
    }

    /**
     * 有效防御 = Σ(数量 × (单位防御 + 单位生命 × hpDefenseWeight) × (1 + 各类防御加成))。
     *
     * <p>hp 项是对 B00 原文的<b>有意扩展</b>，理由与回退方式见
     * {@link BattleRules#hpDefenseWeightFixed()}：原文公式里 hp 不参与任何计算，
     * 而它是玩家看得见的属性，不能是死属性。权重设为 0 即精确还原 B00 原式。
     */
    private static long effectiveDefense(BattleInput input, SideState side,
                                         Map<UnitType, Long> ownCounts) {
        BattleRules rules = input.rules();
        ArmySide army = side.isAttacker() ? input.attacker() : input.defender();
        // 与有效攻击同一条口径：守方的生存力必须按守方自己的阶级属性算
        Map<UnitType, UnitStats> ownStats =
                side.isAttacker() ? input.attackerUnitStats() : input.defenderUnitStats();
        long multiplier = FixedPoint.ONE
                + side.heroDefenseBonus()
                + army.techBonus().defenseFixed()
                + army.equipBonusFixed()
                + rules.terrainDefense(input.terrain())
                + side.defenseBuff() - side.defenseDebuff();
        if (multiplier < 0L) {
            // 削防叠满也不该把防御变成负数：那会让减员系数超过 1，损失多于总兵数
            multiplier = 0L;
        }
        long total = 0L;
        for (UnitType type : UnitType.values()) {
            long count = ownCounts.get(type);
            if (count <= 0L) {
                continue;
            }
            UnitStats stats = ownStats.get(type);
            // 单位生存力 = 防御 + 生命 × 权重。权重为 0 时退化成 B00 原式
            long survivability = stats.defenseFixed()
                    + FixedPoint.mul(stats.hpFixed(), rules.hpDefenseWeightFixed());
            total += FixedPoint.mul(FixedPoint.of(count), FixedPoint.mul(survivability, multiplier));
        }
        return total;
    }

    private static boolean appliesToBuilding(BattleInput input) {
        return input.battleType() == BattleType.SIEGE || input.terrain() == TerrainType.CITY_WALL;
    }

    /** 减员系数 = 己方攻击 / (己方攻击 + 对方防御 × K)。 */
    private static long attrition(long attack, long enemyDefense, long k) {
        long denominator = attack + FixedPoint.mul(enemyDefense, k);
        if (denominator <= 0L) {
            return 0L;
        }
        return FixedPoint.div(attack, denominator);
    }

    private static long jitter(Rng roundRng, long salt, BattleRules rules) {
        return roundRng.fork(salt).nextFixed(rules.jitterMinFixed(), rules.jitterMaxFixed());
    }

    /**
     * 克制倍率：A 克 B 时 ×(1+bonus)，B 克 A 时 ×(1-penalty)。
     *
     * <p>相互克制（如步兵↔弓兵）两项同时生效，1.25 × 0.80 = 1.0，净效应中性 ——
     * 这类对局的胜负由裸数值与数量决定，天然接近 50% 胜率。
     */
    private static long counterMultiplier(UnitType attacker, UnitType defender, BattleRules rules) {
        long result = FixedPoint.ONE;
        if (rules.counters(attacker, defender)) {
            result = FixedPoint.mul(result, FixedPoint.ONE + rules.counterBonusFixed());
        }
        if (rules.counters(defender, attacker)) {
            result = FixedPoint.mul(result, FixedPoint.ONE - rules.counterPenaltyFixed());
        }
        return result;
    }

    private static long weightedCounter(UnitType attacker, Map<UnitType, Long> enemyCounts,
                                        long enemyTotal, BattleRules rules) {
        if (enemyTotal <= 0L) {
            return FixedPoint.ONE;
        }
        long acc = 0L;
        for (UnitType defender : UnitType.values()) {
            long count = enemyCounts.get(defender);
            if (count <= 0L) {
                continue;
            }
            long weight = FixedPoint.div(FixedPoint.of(count), FixedPoint.of(enemyTotal));
            acc += FixedPoint.mul(weight, counterMultiplier(attacker, defender, rules));
        }
        return acc;
    }

    /**
     * 8 回合未分胜负：按剩余兵力百分比判胜，差距小于阈值判平局（B05 验收 11）。
     *
     * <p>用剩余<b>百分比</b>而不是绝对值：10 万打 1 千时绝对值差永远巨大，
     * 百分比才反映双方各自的战损效率，是「这场仗谁打得更好」的度量。
     */
    private static Winner judgeByRemainingRatio(SideState atk, SideState def, long drawGapFixed) {
        long atkRatio = remainingRatio(atk);
        long defRatio = remainingRatio(def);
        if (FixedPoint.abs(atkRatio - defRatio) < drawGapFixed) {
            return Winner.DRAW;
        }
        return atkRatio > defRatio ? Winner.ATTACKER : Winner.DEFENDER;
    }

    private static long remainingRatio(SideState side) {
        if (side.initialUnits() <= 0L) {
            return 0L;
        }
        return FixedPoint.div(FixedPoint.of(side.totalUnits()), FixedPoint.of(side.initialUnits()));
    }

    /**
     * 死亡/伤兵拆分与医院溢出（B00 伤兵规则）：先按比例拆死伤，
     * 再让医院容量吃掉伤兵，吃不下的一律转死亡 —— 即「医院溢出部分直接死亡」。
     */
    private static Casualties casualties(long totalLoss, long hospitalCapacity, long deadRatioFixed) {
        long dead = FixedPoint.round(FixedPoint.mul(FixedPoint.of(totalLoss), deadRatioFixed));
        long wounded = totalLoss - dead;
        long overflow = wounded > hospitalCapacity ? wounded - hospitalCapacity : 0L;
        return new Casualties(dead + overflow, wounded - overflow, overflow);
    }

    /** 攻方剩余负载 = Σ(存活数量 × 单位负载)，是掠夺量上限（B00）。 */
    private static long remainingLoad(Map<UnitType, Long> initial, Map<UnitType, Long> survivors,
                                      Map<UnitType, UnitStats> stats) {
        long load = 0L;
        for (UnitType type : UnitType.values()) {
            long alive = Math.min(survivors.getOrDefault(type, 0L), initial.getOrDefault(type, 0L));
            load += alive * stats.get(type).load();
        }
        return load;
    }

    /**
     * 掠夺结算：min(对方非保护资源, 我方剩余负载)，且必须破墙、必须非 PVE。
     *
     * <p>按资源 id 字典序贪心填充（{@link DefenderStore} 已把 Map 排好序）。
     * 贪心顺序必须固定，否则同一场战斗掠到的资源组合会变。
     * TODO(需确认): 分配策略（字典序贪心 vs 按守方存量比例）属于 B04 资源背包批次的领域，
     * 这里先用字典序贪心保证确定性，B04 定稿后替换。
     */
    private static Map<String, Long> settleLoot(BattleInput input, long lootCapacity) {
        Map<String, Long> loot = new TreeMap<>();
        DefenderStore store = input.defenderStore();
        if (store == null || !store.wallBroken() || input.battleType() == BattleType.PVE) {
            return loot;
        }
        long remaining = lootCapacity;
        for (Map.Entry<String, Long> e : store.unprotected().entrySet()) {
            if (remaining <= 0L) {
                break;
            }
            long take = Math.min(e.getValue(), remaining);
            if (take > 0L) {
                loot.put(e.getKey(), take);
                remaining -= take;
            }
        }
        return loot;
    }

    /** 兵种在标准阵型中的排位置：步兵前排、骑兵中排、弓兵与攻城器后排。 */
    static int rowOf(UnitType type) {
        return switch (type) {
            case INFANTRY -> ROW_FRONT;
            case CAVALRY -> ROW_MID;
            case ARCHER, SIEGE -> ROW_BACK;
        };
    }

    private static long sum(Map<UnitType, Long> counts) {
        long total = 0L;
        for (UnitType type : UnitType.values()) {
            total += counts.getOrDefault(type, 0L);
        }
        return total;
    }

    private record Casualties(long dead, long wounded, long overflowDead) {
    }

    // ---------- 单方战斗状态 ----------

    /**
     * 一方在战斗过程中的可变状态。
     *
     * <p>这是内核里唯一的可变对象，生命周期严格限制在一次 simulate() 调用内，
     * 不逃逸、不共享 —— 因此不破坏「纯函数」与「线程安全」两条约束。
     */
    private static final class SideState {
        private final ArmySide army;
        private final boolean attacker;
        private final Map<UnitType, Long> counts = new EnumMap<>(UnitType.class);
        /** 本场累计损失。HEAL 只能从这个池子里往回捞，不能凭空造兵。 */
        private final Map<UnitType, Long> cumulativeLoss = new EnumMap<>(UnitType.class);
        private final List<ActiveEffect> effects = new ArrayList<>();
        private final long initialUnits;
        private long pendingHealRatio;
        private long pendingExtraDamageRatio;

        SideState(ArmySide army, boolean attacker) {
            this.army = army;
            this.attacker = attacker;
            for (UnitType type : UnitType.values()) {
                counts.put(type, army.units().get(type));
                cumulativeLoss.put(type, 0L);
            }
            this.initialUnits = army.totalUnits();
        }

        boolean isAttacker() {
            return attacker;
        }

        long initialUnits() {
            return initialUnits;
        }

        long hospitalCapacity() {
            return army.hospitalCapacity();
        }

        Map<UnitType, Long> counts() {
            return counts;
        }

        Map<UnitType, Long> snapshotCounts() {
            return new EnumMap<>(counts);
        }

        long totalUnits() {
            return ArmySide.totalOf(counts);
        }

        long totalLost() {
            return ArmySide.totalOf(cumulativeLoss);
        }

        /**
         * 增援（BOSS 的 REINFORCEMENT 机制）。
         *
         * <p><b>只加 counts，不动 cumulativeLoss</b>：援军是新到的兵，不是「捞回来的损失」。
         * 若把它记进损失池，战后的死亡/伤兵拆算就会把从未受伤的新兵算成伤亡，
         * 而 HEAL 也能把它们「治疗」回去 —— 那等于凭空造兵。
         */
        void reinforce(Map<UnitType, Long> added) {
            if (added == null) {
                return;
            }
            added.forEach((type, count) -> {
                if (count != null && count > 0L) {
                    counts.put(type, counts.getOrDefault(type, 0L) + count);
                }
            });
        }

        long heroAttackBonus() {
            long total = 0L;
            for (HeroSnapshot h : army.heroesBySlot()) {
                total += h.heroBonusFixed();
            }
            return total;
        }

        long heroDefenseBonus() {
            long total = 0L;
            for (HeroSnapshot h : army.heroesBySlot()) {
                total += h.defBonusFixed();
            }
            return total;
        }

        long attackBuff() {
            return sumEffect(SkillEffect.BUFF_ATK);
        }

        long attackDebuff() {
            return sumEffect(SkillEffect.DEBUFF_ATK);
        }

        long defenseBuff() {
            return sumEffect(SkillEffect.BUFF_DEF);
        }

        long defenseDebuff() {
            return sumEffect(SkillEffect.DEBUFF_DEF);
        }

        private long sumEffect(SkillEffect effect) {
            long total = 0L;
            for (ActiveEffect e : effects) {
                if (e.effect() == effect) {
                    total += e.valueFixed();
                }
            }
            return total;
        }

        /** 回合开始：递减持续时间、清掉过期效果、重置本回合的一次性数值。 */
        void tickEffects() {
            effects.removeIf(e -> {
                e.decrement();
                return e.remainingRounds() <= 0;
            });
            pendingHealRatio = 0L;
            pendingExtraDamageRatio = 0L;
        }

        long pendingExtraDamageRatio() {
            return pendingExtraDamageRatio;
        }

        /**
         * 按固定顺序施放某一阶段的技能：武将按 slot 升序，同武将按技能列表顺序。
         *
         * @param enemy    敌方状态。DEBUFF 类效果作用在敌方身上，所以必须能拿到它
         * @param triggers 触发记录收集器，按施放顺序追加
         */
        void castSkills(BattleInput input, int round, Rng roundRng, SkillPhase phase,
                        SideState enemy, List<SkillTrigger> triggers) {
            for (HeroSnapshot hero : army.heroesBySlot()) {
                List<SkillSnapshot> skills = hero.skills();
                for (int i = 0; i < skills.size(); i++) {
                    SkillSnapshot skill = skills.get(i);
                    if (skill.phase() != phase) {
                        continue;
                    }
                    if (!forkFor(roundRng, phase, hero.slot(), i).chance(skill.chanceFixed())) {
                        continue;
                    }
                    applySkill(round, phase, hero, skill, enemy, triggers);
                }
            }
        }

        /**
         * fork salt 由 (回合, 阶段, 站位, 技能序号, 阵营) 唯一确定。
         * 回合因子由调用方传入的 roundRng 承担（它本身是 battle.fork(round)）。
         */
        private Rng forkFor(Rng roundRng, SkillPhase phase, int slot, int skillIndex) {
            long salt = SALT_SKILL_BASE
                    + phase.ordinal() * SALT_PHASE_STEP
                    + slot * SALT_SKILL_INDEX_STEP
                    + skillIndex
                    + (attacker ? 0L : SALT_SIDE_DEFENDER);
            return roundRng.fork(salt);
        }

        private void applySkill(int round, SkillPhase phase, HeroSnapshot hero, SkillSnapshot skill,
                                SideState enemy, List<SkillTrigger> triggers) {
            long applied = skill.valueFixed();
            switch (skill.effect()) {
                // 增益作用于自己，减益作用于敌方 —— 两者都记成 ActiveEffect，
                // 由 effectiveAttack / effectiveDefense 在合成乘区时分别读取
                case BUFF_ATK, BUFF_DEF ->
                        effects.add(new ActiveEffect(skill.effect(), applied, skill.durationRounds()));
                case DEBUFF_ATK, DEBUFF_DEF ->
                        enemy.effects.add(new ActiveEffect(skill.effect(), applied, skill.durationRounds()));
                case DAMAGE -> pendingExtraDamageRatio += applied;
                case HEAL -> pendingHealRatio += applied;
                case SKIP_TURN ->
                        // 刻意不实现硬控：见 SkillEffect.SKIP_TURN 注释
                        // （被硬控的玩家什么也没做就输了，不会想叫人反打，只会想退游）
                        applied = 0L;
            }
            triggers.add(new SkillTrigger(round, phase, hero.heroId(), hero.slot(),
                    skill.skillId(), skill.effect(), skill.valueFixed(), applied));
        }

        /**
         * 落地损失，按前排 0.5 / 中排 0.3 / 后排 0.2 分摊。
         *
         * <p><b>分摊比例是「优先级」而不是「上限」</b>：某排兵力不足以承担其份额时，
         * 溢出必须先顺延到后续排；后续排也吃不下时，再<b>回流</b>到前面仍有兵力的排。
         * 只有当全部三排都没有兵力时，剩余损失才允许被丢弃（那意味着全军已覆没）。
         *
         * <p>为什么必须有回流：只向后顺延会让「单一兵种军队」凭空少受伤 ——
         * 纯步兵全在前排，中后排为空，于是 50% 的损失无处可去被直接吞掉，
         * 实际承伤只有设计值的一半；纯弓兵在后排更夸张，只承受 20%。
         * 这个 bug 不报错、不产生负数，只会让平衡矩阵整体失真
         * （实测 1000 步兵对 1000 骑兵，守方应损失 67 却只损失 34），
         * 属于最难靠肉眼发现的一类错误。
         *
         * <p>顺序固定：第一轮 前→中→后 按比例，回流轮同样按 前→中→后，
         * 与枚举声明顺序一致，保证可复现。
         */
        Map<UnitType, Long> applyLoss(long loss, BattleRules rules) {
            Map<UnitType, Long> lost = new EnumMap<>(UnitType.class);
            for (UnitType type : UnitType.values()) {
                lost.put(type, 0L);
            }
            if (loss <= 0L) {
                return lost;
            }
            long frontShare = FixedPoint.round(FixedPoint.mul(FixedPoint.of(loss), rules.rowFrontFixed()));
            long midShare = FixedPoint.round(FixedPoint.mul(FixedPoint.of(loss), rules.rowMidFixed()));
            // 后排吃掉余数，保证三排份额之和精确等于 loss（避免三次独立取整产生漂移）
            long[] shares = {frontShare, midShare, loss - frontShare - midShare};

            long overflow = 0L;
            for (int row = ROW_FRONT; row <= ROW_BACK; row++) {
                long target = shares[row] + overflow;
                long take = Math.min(target, rowCapacity(row));
                overflow = target - take;
                if (take > 0L) {
                    distributeWithinRow(row, take, lost);
                }
            }
            // 回流：仍有损失无处安放时，按 前→中→后 顺序填给还有兵力的排，
            // 直到损失分完或全军覆没。最多三排，所以三轮必然收敛
            for (int pass = 0; overflow > 0L && pass <= ROW_BACK; pass++) {
                for (int row = ROW_FRONT; row <= ROW_BACK && overflow > 0L; row++) {
                    long capacity = rowCapacity(row) - rowAlreadyLost(row, lost);
                    if (capacity <= 0L) {
                        continue;
                    }
                    long take = Math.min(overflow, capacity);
                    distributeWithinRow(row, take, lost);
                    overflow -= take;
                }
            }
            for (UnitType type : UnitType.values()) {
                long l = lost.get(type);
                if (l > 0L) {
                    counts.put(type, counts.get(type) - l);
                    cumulativeLoss.put(type, cumulativeLoss.get(type) + l);
                }
            }
            return lost;
        }

        /** 某排在本轮已分摊到的损失，用于计算回流时的剩余容量。 */
        private long rowAlreadyLost(int row, Map<UnitType, Long> lost) {
            long total = 0L;
            for (UnitType type : UnitType.values()) {
                if (rowOf(type) == row) {
                    total += lost.get(type);
                }
            }
            return total;
        }

        /** 把治疗量从本场累计损失池里捞回来，按各兵种损失占比分配。 */
        Map<UnitType, Long> applyHeal() {
            Map<UnitType, Long> healed = new EnumMap<>(UnitType.class);
            for (UnitType type : UnitType.values()) {
                healed.put(type, 0L);
            }
            long totalLost = totalLost();
            if (pendingHealRatio <= 0L || totalLost <= 0L) {
                return healed;
            }
            long amount = Math.min(
                    FixedPoint.round(FixedPoint.mul(FixedPoint.of(totalLost), pendingHealRatio)),
                    totalLost);
            if (amount <= 0L) {
                return healed;
            }
            List<UnitType> lostTypes = new ArrayList<>();
            for (UnitType type : UnitType.values()) {
                if (cumulativeLoss.get(type) > 0L) {
                    lostTypes.add(type);
                }
            }
            long allocated = 0L;
            for (int i = 0; i < lostTypes.size(); i++) {
                UnitType type = lostTypes.get(i);
                long cap = cumulativeLoss.get(type);
                // 最后一个兵种吃掉余数，保证分配总量精确等于 amount
                long share = (i == lostTypes.size() - 1) ? amount - allocated : amount * cap / totalLost;
                share = Math.max(0L, Math.min(share, cap));
                healed.put(type, share);
                allocated += share;
            }
            for (UnitType type : UnitType.values()) {
                long h = healed.get(type);
                if (h > 0L) {
                    counts.put(type, counts.get(type) + h);
                    cumulativeLoss.put(type, cumulativeLoss.get(type) - h);
                }
            }
            return healed;
        }

        private long rowCapacity(int row) {
            long capacity = 0L;
            for (UnitType type : UnitType.values()) {
                if (rowOf(type) == row) {
                    capacity += counts.get(type);
                }
            }
            return capacity;
        }

        /** 排内按各兵种现有数量占比分配，最后一个兵种吃掉余数保证总和精确。 */
        private void distributeWithinRow(int row, long take, Map<UnitType, Long> lost) {
            List<UnitType> rowTypes = new ArrayList<>();
            long rowTotal = 0L;
            for (UnitType type : UnitType.values()) {
                long remainingCount = counts.get(type) - lost.get(type);
                if (rowOf(type) == row && remainingCount > 0L) {
                    rowTypes.add(type);
                    rowTotal += remainingCount;
                }
            }
            if (rowTypes.isEmpty()) {
                return;
            }
            long allocated = 0L;
            for (int i = 0; i < rowTypes.size(); i++) {
                UnitType type = rowTypes.get(i);
                // 上限要扣掉本轮已分摊的损失，否则回流轮会把同一个兵种扣成负数
                long count = counts.get(type) - lost.get(type);
                long share = (i == rowTypes.size() - 1) ? take - allocated : take * count / rowTotal;
                share = Math.max(0L, Math.min(share, count));
                lost.put(type, lost.get(type) + share);
                allocated += share;
            }
        }
    }

    /** 一个正在生效的技能效果。 */
    private static final class ActiveEffect {
        private final SkillEffect effect;
        private final long valueFixed;
        private int remainingRounds;

        ActiveEffect(SkillEffect effect, long valueFixed, int durationRounds) {
            this.effect = effect;
            this.valueFixed = valueFixed;
            this.remainingRounds = durationRounds;
        }

        SkillEffect effect() {
            return effect;
        }

        long valueFixed() {
            return valueFixed;
        }

        int remainingRounds() {
            return remainingRounds;
        }

        void decrement() {
            remainingRounds--;
        }
    }
}
