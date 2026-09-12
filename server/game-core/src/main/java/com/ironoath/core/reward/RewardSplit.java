package com.ironoath.core.reward;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 职责：把一笔<b>收益</b>按权重分给多个人（集结合并行军的掠夺与掉落分摊，B10 验收 11 的另一半）。
 * 依赖：无（纯 Java，零框架、零配置）。
 *
 * <p><b>为什么不是 {@code TierSplit}</b>：那一个分的是<b>损失</b>，语义上要求
 * 「单个阶级承担的损失不得超过它现有的数量」，所以它把总量夹到 {@code min(loss, Σweights)}。
 * 收益没有这个上限 —— 500 兵分 12,000 单位木头，按它的实现会只分出 500 就返回，
 * 剩下 11,500 静默消失。两个语义共用一份实现一定会出事，差别还正好是「会不会凭空少给玩家东西」。
 *
 * <p><b>Σ分回 == amount 是硬要求</b>：分摊收益的所有坑都是同一个形状 ——
 * 零头既不算分给谁、也没被别处拿走，于是国库与玩家钱包对不上账，而两边日志都显示正常。
 * 与 B05 那个「某排为空时它那一份损失直接消失」的吞兵 bug 同族。
 *
 * <p><b>余数按 id 字典序决定归属</b>（铁律 4：同一场战斗重放必须得到同一份分配）。
 * 谁多拿一个零头是玩家能感觉到的不公平，所以它必须是确定的、可写进文档的，
 * 而不是「HashMap 迭代顺序说了算」。
 */
public final class RewardSplit {

    private RewardSplit() {
    }

    /**
     * 按权重分摊一笔收益。
     *
     * @param weights 受益人 → 权重（集结场景下用「本人承诺的兵力总数」）。null 值与非正权重被忽略
     * @param amount  要分的总量，必须 &gt;= 0
     * @return 受益人 → 分得量，只包含分到手的项（可能有人为 0）；<b>Σ返回值 == amount</b>
     * @throws IllegalArgumentException 金额为负、或没有任何有效权重
     */
    public static Map<String, Long> byWeight(Map<String, Long> weights, long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("收益分摊的金额不得为负：" + amount);
        }
        Map<String, Long> valid = new TreeMap<>();
        long totalWeight = 0L;
        for (Map.Entry<String, Long> e : (weights == null ? Map.<String, Long>of() : weights).entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null || e.getValue() <= 0L) {
                continue;
            }
            valid.put(e.getKey(), e.getValue());
            totalWeight += e.getValue();
        }
        if (amount == 0L) {
            return Map.of();
        }
        if (valid.isEmpty() || totalWeight <= 0L) {
            throw new IllegalArgumentException("收益分摊没有任何有效权重，这笔钱无法如实分出去 amount=" + amount);
        }

        long[] shares = new long[valid.size()];
        long[] remainders = new long[valid.size()];
        List<String> ids = new ArrayList<>(valid.keySet());
        long assigned = 0L;
        int i = 0;
        for (String id : ids) {
            long weight = valid.get(id);
            shares[i] = multiplyDivide(weight, amount, totalWeight);
            remainders[i] = multiplyModulo(weight, amount, totalWeight);
            assigned += shares[i];
            i++;
        }
        // 最大余数法：零头按余数从大到小逐个 +1，余数相同则按 id 字典序小者优先。
        // 这里不需要像 TierSplit 那样多轮补 —— 那份实现有「单人不得超过其现有数量」的上界，
        // 而收益没有上界，且 Σfloor(x) > x - n，所以差额恒小于受益人数，一轮必然收敛
        long missing = amount - assigned;
        if (missing > 0L) {
            List<Integer> order = new ArrayList<>(ids.size());
            for (int k = 0; k < ids.size(); k++) {
                order.add(k);
            }
            order.sort(Comparator.comparingLong((Integer k) -> -remainders[k]).thenComparing(ids::get));
            if (missing > order.size()) {
                // 上面那条不等式保证了走不到这里；走到了就是整数运算有误。
                // 必须当场炸而不是少给玩家 —— 少给不会报错，只会让国库与钱包对不上账
                throw new IllegalStateException("收益分摊的零头超出受益人数：amount=" + amount
                        + ", totalWeight=" + totalWeight + ", 仍缺 " + missing + ", 受益人=" + ids.size());
            }
            for (int k = 0; k < missing; k++) {
                shares[order.get(k)]++;
            }
        }

        Map<String, Long> out = new LinkedHashMap<>();
        for (int k = 0; k < ids.size(); k++) {
            if (shares[k] > 0L) {
                out.put(ids.get(k), shares[k]);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** weight × amount ÷ total，向下取整。乘积可能溢出 long（大额收益 × 大权重），用 BigInteger 兜底。 */
    private static long multiplyDivide(long weight, long amount, long total) {
        try {
            return Math.multiplyExact(weight, amount) / total;
        } catch (ArithmeticException overflow) {
            return java.math.BigInteger.valueOf(weight).multiply(java.math.BigInteger.valueOf(amount))
                    .divide(java.math.BigInteger.valueOf(total)).longValueExact();
        }
    }

    /** weight × amount ÷ total 的余数，用于最大余数法排序。 */
    private static long multiplyModulo(long weight, long amount, long total) {
        try {
            return Math.multiplyExact(weight, amount) % total;
        } catch (ArithmeticException overflow) {
            return java.math.BigInteger.valueOf(weight).multiply(java.math.BigInteger.valueOf(amount))
                    .mod(java.math.BigInteger.valueOf(total)).longValue();
        }
    }

    /** 供调用方断言用：把分摊结果重新求和，省得每处都写一遍循环。 */
    public static long sum(Map<String, Long> shares) {
        long total = 0L;
        for (long v : shares.values()) {
            total += v;
        }
        return total;
    }
}
