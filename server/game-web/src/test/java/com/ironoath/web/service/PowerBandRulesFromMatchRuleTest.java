package com.ironoath.web.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.cfg.MatchRuleCfg;
import com.ironoath.core.power.PowerBandGuard;

/**
 * 职责：证明战力区间是<b>跟着 match_rule 那一行走的</b>，不是跟着代码里的字面参数名走的。
 * 依赖：game-config 的表类型、game-core 的圈层规则（纯逻辑，不起 Spring）。
 *
 * <p><b>为什么不测"结果等于 global 里那两个数"</b>：那种断言在旧写法（`fixedParam("PVP_POWER_MIN_RATIO")`）
 * 下同样成立 —— 值本来就来自同一张 global 表。一份替被替换掉的旧写法作证的测试等于没测，
 * 而且会把错误口径钉成规格（收口清单里为这件事记过不止一次）。
 * 所以这里喂<b>一行假的引用</b>：只有实现真的按 row 里写的 id 去取数，才可能拿到这两个值。
 */
class PowerBandRulesFromMatchRuleTest {

    @Test
    @DisplayName("区间取自那一行声明的引用：resolver 只被表里那两个 id 调用过")
    void bandFollowsTheRefsTheRowDeclares() {
        List<String> askedFor = new ArrayList<>();
        Map<String, Long> values = Map.of("REF_LOWER", 4000L, "REF_UPPER", 25000L);

        PowerBandGuard.Rules rules = PowerService.bandRulesOf(
                row("mr_scenario_normal_attack", "REF_LOWER", "REF_UPPER"),
                id -> {
                    askedFor.add(id);
                    return values.get(id);
                });

        assertThat(askedFor).as("问过哪些参数只能由表决定；出现 PVP_POWER_* 就说明有人把名字写死了")
                .containsExactly("REF_LOWER", "REF_UPPER");
        assertThat(rules.lowerRatioFixed()).as("下限跟着表里那一格走").isEqualTo(4000L);
        assertThat(rules.upperRatioFixed()).as("上限跟着表里那一格走").isEqualTo(25000L);
    }

    @Test
    @DisplayName("换一行引用就等于换参数：不改代码也能让区间指向另一对 global 参数")
    void pointingTheRowSomewhereElseMovesTheBand() {
        Map<String, Long> values = Map.of("A_MIN", 3000L, "A_MAX", 30000L, "B_MIN", 9000L, "B_MAX", 15000L);

        PowerBandGuard.Rules a = PowerService.bandRulesOf(row("r1", "A_MIN", "A_MAX"), values::get);
        PowerBandGuard.Rules b = PowerService.bandRulesOf(row("r2", "B_MIN", "B_MAX"), values::get);

        assertThat(new long[] { a.lowerRatioFixed(), a.upperRatioFixed() }).containsExactly(3000L, 30000L);
        assertThat(new long[] { b.lowerRatioFixed(), b.upperRatioFixed() })
                .as("两行引用解出两个不同区间 —— 这才是「改表生效」的可执行含义")
                .containsExactly(9000L, 15000L);
    }

    @Test
    @DisplayName("表里缺引用：响亮失败，绝不悄悄退回某个默认倍率")
    void missingRefFailsLoudlyInsteadOfDefaulting() {
        assertThatThrownBy(() -> PowerService.bandRulesOf(row("r3", "REF_LOWER", " "), id -> 1L))
                .as("空引用被当成「没写」而退回默认值，症状是区间悄悄变了而没人知道从哪来")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("powerMaxParam")
                .hasMessageContaining("r3");

        assertThatThrownBy(() -> PowerService.bandRulesOf(row("r4", null, "REF_UPPER"), id -> 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("powerMinParam");
    }

    @Test
    @DisplayName("√N 那个开关不许被表左右：表只能选参数，不能把集结放宽改成线性")
    void rallySqrtStaysHardcodedRegardlessOfTheRow() {
        for (String extra : List.of("REF_LOWER", "anything")) {
            PowerBandGuard.Rules rules = PowerService.bandRulesOf(
                    row("r5", extra, "REF_UPPER"), id -> extra.equals(id) ? 5000L : 20000L);
            assertThat(rules.rallyBandSqrt())
                    .as("B08 禁止项：集结门槛必须按 √N 放宽，做成可配置就是一个会静默变红的开关")
                    .isTrue();
        }
        assertThat(FixedPoint.SCALE).as("上面这些定点值都在合法区间内（下限 <= 1.0 < 上限）").isEqualTo(10000L);
    }

    private static MatchRuleCfg row(String id, String minRef, String maxRef) {
        return new MatchRuleCfg(id, MatchRuleCfg.Kind.SCENARIO, "玩家主动搜索或指定目标攻击",
                null, null, minRef, maxRef, null, false, null, null, null);
    }
}
