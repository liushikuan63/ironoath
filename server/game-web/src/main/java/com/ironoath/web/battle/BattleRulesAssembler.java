package com.ironoath.web.battle;

import com.ironoath.battle.BattleRules;
import com.ironoath.battle.BossMechanic;
import com.ironoath.config.BattleParams;
import com.ironoath.config.ConfigRegistry;
import org.springframework.stereotype.Component;

/**
 * 职责：为线上战斗装配 {@link BattleRules}（B09 战斗接线的规则侧入口）。
 * 依赖：game-config 的 {@link BattleParams}、game-battle 的 {@code BattleRules.from}。
 *
 * <p><b>本类不读任何具体参数</b>：数值在 {@code BattleParams.of(configs)}，
 * 兵种名到 {@code UnitType} 的映射在 {@code BattleRules.from}。
 * 这两处同时被调数值的 balance-sim CLI 使用，所以「线上跑的」与「调好的」在结构上不可能是两套 ——
 * 这正是把本类写得这么薄的原因：任何在这里出现的 {@code configs.fixedParam("...")}
 * 都意味着线上多了一个 CLI 看不见的数值来源。
 *
 * <p><b>每次调用都重新装配，不缓存</b>：配置表支持热更（{@code ConfigRegistry.reload}），
 * 缓存一份规则会让热更在战斗这条路径上失效 —— 而战斗参数恰恰是最需要能热更的
 * （线上发现某个数值不对，等一次发版再改是不可接受的）。
 * 装配本身是十几次 map 查询，相对于一场战斗的计算量可以忽略。
 */
@Component
public class BattleRulesAssembler {

    private final ConfigRegistry configs;

    public BattleRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 当前配置下的战斗规则。 */
    public BattleRules rules() {
        return BattleRules.from(BattleParams.of(configs));
    }

    /**
     * 按关卡声明的类型装配 BOSS 机制（B09 §二 / 验收 5）。
     *
     * <p>类型来自 stage 表（策划决定哪一关用哪种机制），参数来自 global 表
     * （数值只有一处可调）。两者分开是必要的：若把参数也写进 stage 表的每一行，
     * 调一次平衡就要改 5 行，而漏改一行的表现是「只有某个 BOSS 不对劲」——
     * 那是最难定位的一类数值问题。
     */
    public BossMechanic bossMechanic(com.ironoath.config.cfg.StageCfg.BossMechanic declared) {
        if (declared == null) {
            throw new IllegalArgumentException("declared 不得为 null，无机制请传 BossMechanic.NONE");
        }
        return switch (declared) {
            case NONE -> BossMechanic.none();
            case REINFORCEMENT -> BossMechanic.reinforcement(
                    configs.longParam("BOSS_REINFORCE_INTERVAL_ROUNDS"),
                    configs.fixedParam("BOSS_REINFORCE_RATIO"));
            case SHIELD_PHASE -> BossMechanic.shieldPhase(
                    configs.fixedParam("BOSS_SHIELD_HP_THRESHOLD"),
                    configs.fixedParam("BOSS_SHIELD_DEF_BONUS"));
            case COUNTER_STRIKE -> BossMechanic.counterStrike(
                    configs.fixedParam("BOSS_COUNTER_REFLECT_RATIO"));
        };
    }
}
