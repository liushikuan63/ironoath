package com.ironoath.common.config;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 科技加成的<b>唯一算法真源</b>：Σ（该属性的每一行：每级幅度 × 当前等级）。
 *
 * <p><b>为什么落在 game-common 而不是 game-core（收口清单 #636）</b>：
 * 线上算加成的 {@code TechEffects} 在 <b>game-web</b>，而它依赖的两样东西分属两个互不依赖的模块 ——
 * {@code TechCfg} 在 <b>game-config</b>、{@code PlayerTech} 在 <b>game-core</b>；而
 * {@code scripts/check-layering.sh} 的规则 3/5 写死了
 * 「game-config 只能依赖 game-common」「game-core 只能依赖 game-common」
 * ⇒ 这两层<b>互相不能依赖</b>，谁也装不下这段算法。game-common 是它们共同的下游，
 * 也是唯一放得下、又不会碰层门禁的位置。
 *
 * <p><b>为什么入参用 {@link Row} 与 {@link ToIntFunction} 而不是 {@code TechCfg}/{@code PlayerTech}</b>：
 * 正是为了让本类不 import 那两个模块的任何类型（上面那条约束的直接后果）。
 * 两边各写几行「把行喂进来」的适配即可，而<b>算法只有这一份、不会漂移</b> ——
 * 这正是 #592/#594 反复踩的「模拟器与线上各算一套、悄悄漂移」的根治。
 *
 * <p>用法：{@code TechBonusCore.totalPercent(rows, attrOf, playerTech::levelOf)}。
 * 没研究过的行按 0 级计（{@code levelOf} 缺失即 0 由账本保证），{@code levelOf} 返回 0 的行被跳过。
 *
 * <p><b>全程 long 定点</b>：与 B05 验收 12 同一口径，不引入任何浮点。
 */
public final class TechBonusCore {

    private TechBonusCore() {
        throw new AssertionError("工具类不得实例化");
    }

    /**
     * 一行科技的最小信息：调用方从各自的 {@code TechCfg} 里取出这三样喂进来。
     *
     * @param id           科技 id（用于查等级）
     * @param effectAttr   效果属性（区分「产量」「建造速度」等互不相干的乘区）
     * @param effectValue  <b>每级</b>幅度（定点整数，调用方累加前还要乘等级）
     */
    public record Row(String id, String effectAttr, long effectValue) {
        public Row {
            if (id == null || effectAttr == null) {
                throw new IllegalArgumentException("id 与 effectAttr 不得为 null");
            }
        }
    }

    /**
     * Σ（该属性的每一行：每级幅度 × 当前等级）。
     *
     * @param rows    全部科技行（含不属于该属性的行，这里按 {@code attr} 过滤）
     * @param attr    目标乘区标识（与 {@link Row#effectAttr()} 比对）
     * @param levelOf 取出某科技的当前等级（未研究过应返回 0）
     * @return 定点百分数之和；{@code rows} 或 {@code levelOf} 为 null 时返回 0（等价「一行都没研究过」）
     */
    public static long totalPercent(List<Row> rows, String attr, ToIntFunction<String> levelOf) {
        if (rows == null || levelOf == null || attr == null) {
            return 0L;
        }
        long total = 0L;
        for (Row row : rows) {
            if (!row.effectAttr().equals(attr)) {
                continue;
            }
            int level = levelOf.applyAsInt(row.id());
            if (level > 0) {
                total += row.effectValue() * level;
            }
        }
        return total;
    }
}
