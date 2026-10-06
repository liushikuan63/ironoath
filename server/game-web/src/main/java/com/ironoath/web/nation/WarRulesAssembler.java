package com.ironoath.web.nation;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.WarScoreBoard;

/**
 * 职责：把 global 表的 WAR_* 那九行装配成 {@link WarScoreBoard.Rules}（铁律 1：数值零硬编码）。
 * 依赖：game-config、game-core。
 *
 * <p>与 {@code NationRulesAssembler}、{@code SocialRulesAssembler} 是同一种东西：
 * game-core 按 B00 分层规则读不到配置表，所以国战的每一个数值都必须由外层解析好再传进去。
 *
 * <p><b>为什么每次读都重新装配、不缓存</b>：{@code WarScoreBoard} 的持久化形状（{@code toSnapshot()}）
 * <b>刻意不带规则</b>，理由与 {@code Nation.Snapshot} 同一条 —— 规则会热更，把某一次的值冻进存档，
 * 症状是改了 WAR_* 那几行对已有战事不生效且不报错。代价就是每次重建都要现取一遍规则；
 * 省这个代价的办法是给 {@code WarScoreBoard} 加规则缓存，而那会把上面那条假绿重新请回来。
 *
 * <p><b>单位换算只有一处，但漏了不报错</b>：{@code WAR_DURATION_HOURS} 是小时而
 * {@code Rules.durationMillis} 要毫秒（×3600000）。漏乘的症状是「王城战 3 毫秒就结束」，
 * 而 {@code remainingSeconds} 会一路返回 0 —— 看不出来，因为 0 也是合法的「已结束」。
 * 其余八行都是内核直接吃的单位（分/分钟、分/兵、点、座、次），原样传。
 *
 * <p><b>这里读九行而不是十行</b>：全服目标的<b>奖励金额</b>那一行属「达成后每人领一次」的那笔发放，
 * 领取端点还没接（下一切片），所以本装配器故意不碰它。⚠️ 连它的参数 id 都不在这里写出来：
 * {@code scripts/check-config-consumers.js} 的引用匹配<b>不剥注释</b>（与 {@code check-core-wiring.sh} 相反），
 * 字面量一出现在主源码里，那一行就被算成「生产在读」，于是它的零引用例外登记会<b>静默失效</b>。
 * 想知道这一行现在到底有没有人读，去 {@code contract/config/global.json} 看它那一条的 {@code why}
 * （自陈「运行期无人读它」），别信任何 java 注释。
 */
@Component
public class WarRulesAssembler {

    private static final long MILLIS_PER_HOUR = 3600L * 1000L;

    private final ConfigRegistry configs;

    public WarRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    public WarScoreBoard.Rules rules() {
        return new WarScoreBoard.Rules(
                configs.longParam("WAR_DURATION_HOURS") * MILLIS_PER_HOUR,
                (int) configs.longParam("WAR_GATE_COUNT"),
                configs.longParam("WAR_SCORE_OCCUPY_PER_MINUTE"),
                configs.longParam("WAR_SCORE_KILL_PER_UNIT"),
                configs.longParam("WAR_SCORE_BUILDING_PER_CAPTURE"),
                configs.longParam("WAR_FATIGUE_PER_MARCH"),
                configs.longParam("WAR_FATIGUE_PER_WOUNDED"),
                configs.longParam("WAR_FATIGUE_MAX"),
                configs.longParam("WAR_SERVER_GOAL_KILLS"));
    }
}
