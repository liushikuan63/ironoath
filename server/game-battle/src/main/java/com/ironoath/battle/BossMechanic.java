package com.ironoath.battle;

import com.ironoath.common.num.FixedPoint;

/**
 * 职责：BOSS 机制（B09 §二 / 验收 5）—— 让章末 BOSS「有机制而不是纯数值」。
 * 依赖：game-common 的 FixedPoint（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>为什么 BOSS 必须有机制</b>：纯数值的 BOSS 只能靠练度硬碾，
 * 于是「打不过」的唯一答案是「去变强」，而变强在本项目里意味着充钱。
 * 机制给出第二条路 —— 换阵型、换兵种、换武将，那是免费的、也是有趣的。
 * B09 §二 的原话是「逼玩家换阵型」，三种机制各自逼的东西不同：
 * <ul>
 *   <li>{@link Type#REINFORCEMENT} 每 N 回合召唤援军 ⇒ 逼玩家<b>速攻</b>（带高输出而不是带肉）</li>
 *   <li>{@link Type#SHIELD_PHASE} 血量低于阈值进入防御姿态 ⇒ 逼玩家<b>换兵种</b>（高攻击穿透，
 *       或者在阈值之前就打穿）</li>
 *   <li>{@link Type#COUNTER_STRIKE} 受到攻击时反弹伤害 ⇒ 逼玩家<b>带治疗/减伤武将</b></li>
 * </ul>
 *
 * <p><b>参数由调用方从 global 表装配后传入</b>，本类不读配置（game-battle 只能依赖 game-common）。
 * 三个静态工厂各自只接受自己用得到的参数，用不到的位置填 0 ——
 * 这样「哪种机制用哪个字段」在构造点就是明确的，不需要读文档去猜。
 *
 * @param type           机制类型
 * @param intervalRounds REINFORCEMENT：每几回合召唤一次。其它机制为 0
 * @param ratioFixed     REINFORCEMENT：每次召唤「原始守军」的比例（定点）；
 *                       COUNTER_STRIKE：反弹伤害的比例（定点）。其它机制为 0
 * @param thresholdFixed SHIELD_PHASE：触发防御姿态的剩余兵力比例（定点）。其它机制为 0
 * @param bonusFixed     SHIELD_PHASE：防御姿态下的防御加成（定点）。其它机制为 0
 */
public record BossMechanic(Type type,
                           long intervalRounds,
                           long ratioFixed,
                           long thresholdFixed,
                           long bonusFixed) {

    /**
     * 机制触发记录在战报里的来源标识。
     *
     * <p>{@code SkillTrigger} 要求 heroId 非空（它原本是「某个武将放了某个技能」），
     * 而 BOSS 机制不是武将发出的。用一个具名常量而不是塞一个假武将 id：
     * 战报的读取方要能一眼看出「这条不是武将技能」，
     * 并且据此判定归属方（机制永远属于守方）——
     * 若塞一个真实武将的 id，读取方会把它算成那个武将的技能，战报就错了。
     */
    public static final String TRIGGER_SOURCE = "boss";

    /** 机制类型。名称必须与 stage 表的 bossMechanic ENUM 一致（由 StageContractParityTest 断言）。 */
    public enum Type {
        /** 无机制（普通关卡、野怪、玩家城）。 */
        NONE,
        /** 每 N 回合召唤援军。 */
        REINFORCEMENT,
        /** 剩余兵力低于阈值后进入防御姿态。 */
        SHIELD_PHASE,
        /** 受到攻击时反弹一部分伤害。 */
        COUNTER_STRIKE
    }

    public BossMechanic {
        if (type == null) {
            throw new IllegalArgumentException("type 不得为 null，无机制请用 BossMechanic.none()");
        }
        switch (type) {
            case NONE -> {
                if (intervalRounds != 0L || ratioFixed != 0L || thresholdFixed != 0L || bonusFixed != 0L) {
                    throw new IllegalArgumentException("NONE 机制不得携带任何参数：interval=" + intervalRounds
                            + ", ratio=" + ratioFixed + ", threshold=" + thresholdFixed
                            + ", bonus=" + bonusFixed);
                }
            }
            case REINFORCEMENT -> {
                if (intervalRounds < 1L) {
                    throw new IllegalArgumentException("召唤间隔必须 >= 1 回合，实际=" + intervalRounds
                            + "。0 意味着每回合都召唤，战斗永远打不完");
                }
                requireUnitRatio(ratioFixed, "召唤比例");
                if (ratioFixed <= 0L) {
                    throw new IllegalArgumentException("召唤比例必须为正，否则这个机制什么都不做：" + ratioFixed);
                }
                requireZero(thresholdFixed, "thresholdFixed", type);
                requireZero(bonusFixed, "bonusFixed", type);
            }
            case SHIELD_PHASE -> {
                requireUnitRatio(thresholdFixed, "触发阈值");
                if (thresholdFixed <= 0L || thresholdFixed >= FixedPoint.SCALE) {
                    throw new IllegalArgumentException("触发阈值必须落在 (0, 1.0) 之间，实际=" + thresholdFixed
                            + "。1.0 意味着开局就进防御姿态，0 意味着永远不触发");
                }
                if (bonusFixed <= 0L) {
                    throw new IllegalArgumentException("防御加成必须为正，否则这个机制什么都不做：" + bonusFixed);
                }
                requireZero(intervalRounds, "intervalRounds", type);
                requireZero(ratioFixed, "ratioFixed", type);
            }
            case COUNTER_STRIKE -> {
                requireUnitRatio(ratioFixed, "反弹比例");
                if (ratioFixed <= 0L) {
                    throw new IllegalArgumentException("反弹比例必须为正，否则这个机制什么都不做：" + ratioFixed);
                }
                requireZero(intervalRounds, "intervalRounds", type);
                requireZero(thresholdFixed, "thresholdFixed", type);
                requireZero(bonusFixed, "bonusFixed", type);
            }
        }
    }

    /** 无机制。绝大多数战斗（野怪、玩家城、普通关卡）都是这个。 */
    public static BossMechanic none() {
        return new BossMechanic(Type.NONE, 0L, 0L, 0L, 0L);
    }

    /**
     * 每 {@code intervalRounds} 回合召唤「原始守军 × ratio」的援军。
     *
     * <p>按<b>原始</b>守军而不是当前守军计算：按当前守军算的话，
     * 每次召唤的量会随着守军被打残而递减，机制会在最需要它的时候失效 ——
     * 而它存在的目的恰恰是「你打得太慢就会被拖死」。
     */
    public static BossMechanic reinforcement(long intervalRounds, long ratioFixed) {
        return new BossMechanic(Type.REINFORCEMENT, intervalRounds, ratioFixed, 0L, 0L);
    }

    /** 剩余兵力低于「原始守军 × threshold」时，防御提升 bonus。 */
    public static BossMechanic shieldPhase(long thresholdFixed, long bonusFixed) {
        return new BossMechanic(Type.SHIELD_PHASE, 0L, 0L, thresholdFixed, bonusFixed);
    }

    /** 受到攻击时，把所受伤亡的 reflect 比例反弹给攻方。 */
    public static BossMechanic counterStrike(long reflectFixed) {
        return new BossMechanic(Type.COUNTER_STRIKE, 0L, reflectFixed, 0L, 0L);
    }

    public boolean isNone() {
        return type == Type.NONE;
    }

    /** 本回合（1 起）是否触发召唤。 */
    public boolean summonsOn(int round) {
        return type == Type.REINFORCEMENT && intervalRounds > 0L && round % intervalRounds == 0;
    }

    private static void requireUnitRatio(long fixed, String name) {
        if (fixed < 0L || fixed > FixedPoint.SCALE) {
            throw new IllegalArgumentException(name + "必须落在 [0, 1.0] 的定点区间，实际=" + fixed);
        }
    }

    private static void requireZero(long value, String field, Type type) {
        if (value != 0L) {
            throw new IllegalArgumentException(type + " 机制不使用 " + field + "，必须为 0，实际=" + value
                    + "。填了值说明调用方以为它会生效，而它不会 —— 那是一次静默失效");
        }
    }
}
