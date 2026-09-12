package com.ironoath.core.army;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把一个兵种的总损失按各阶级的现有数量比例摊回去（B09 战斗结算的一环）。
 * 依赖：无（纯 Java，零框架、零配置依赖）。
 *
 * <p><b>为什么需要这个类</b>：战斗内核按 {@code UnitType}（步兵/骑兵/弓兵/攻城器）结算，
 * 返回的是「步兵损失 37 个」；而军队存档按 unitId 记兵（{@code unit_infantry_t1}…{@code unit_infantry_t5}）。
 * 两边粒度不同，中间必须有一次分摊。B07 的做法是「全部按最低阶级入账」，
 * 于是一次出征就能把 T5 兵降成 T1 —— 玩家的顶级兵打完一场仗就没了，
 * 而这个损失不会报错、不会为负，只会在战力上悄悄少一大截。
 *
 * <p><b>按比例摊，而不是「低阶先死」</b>：后者是很多 SLG 的做法（杂兵当炮灰），
 * 但那是一条 B00/B05 都没写过的额外机制，而且它会改变阶级的相对价值 ——
 * 一旦低阶兵是免费肉盾，训练高阶兵就成了纯粹的亏本买卖。
 * 按比例摊是中性的：它不发明任何新规则，只是把「这个兵种损失了多少」如实分到各阶级头上。
 *
 * <p><b>用最大余数法保证 Σ摊回量 == 损失量</b>。这与 B05 修过的三排分摊是同一个 bug 家族：
 * 那个 bug 是「某排为空时它那一份损失直接消失」，导致单一兵种军队的实际承伤只有设计值的一半。
 * 任何按比例分摊的实现都必须断言总量守恒，否则缺口会静默消失 ——
 * 少了兵不报错，多了兵也不报错，只有玩家的战损统计对不上。
 */
public final class TierSplit {

    private TierSplit() {
    }

    /**
     * 按现有数量比例分摊损失。
     *
     * @param counts unitId → 现有数量。必须是<b>同一个兵种</b>的各阶级（不同兵种的数量混在一起
     *               在数学上也能算，但语义上就变成「跨兵种互相顶损失」，那是另一条规则）
     * @param loss   该兵种的总损失
     * @return unitId → 该阶级承担的损失，只包含承担量 &gt; 0 的项；
     *         Σ返回值 == min(loss, Σcounts)
     */
    public static Map<String, Long> splitProportionally(Map<String, Long> counts, long loss) {
        if (loss < 0L) {
            throw new IllegalArgumentException("损失不得为负：" + loss);
        }
        if (counts == null || counts.isEmpty() || loss == 0L) {
            return Map.of();
        }
        // 按 unitId 排序后处理：余数分配的先后必须确定，否则同一场战斗在两次运行里
        // 会把余数给不同的阶级 —— 战报就无法复现（铁律 4）
        List<String> ids = new ArrayList<>(counts.size());
        long total = 0L;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            String id = entry.getKey();
            Long count = entry.getValue();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("unitId 不得为空");
            }
            if (count == null || count < 0L) {
                throw new IllegalArgumentException("数量不得为负：" + id + "=" + count);
            }
            if (count == 0L) {
                continue;   // 已经打光的阶级不参与分摊，也不该出现在结果里
            }
            ids.add(id);
            total += count;
        }
        if (ids.isEmpty() || total <= 0L) {
            return Map.of();
        }
        ids.sort(Comparator.naturalOrder());
        long effective = Math.min(loss, total);

        long[] shares = new long[ids.size()];
        long[] remainders = new long[ids.size()];
        long assigned = 0L;
        for (int i = 0; i < ids.size(); i++) {
            long count = counts.get(ids.get(i));
            // count 与 effective 都不超过 total，而 total 受 TRAIN_BATCH_MAX 与带兵上限约束
            // （现实量级 1e6 以内），所以 count × effective 远在 long 范围内
            shares[i] = count * effective / total;
            remainders[i] = count * effective % total;
            assigned += shares[i];
        }
        // 最大余数法：把剩下的名额按余数从大到小逐个补齐，余数相同则按 unitId 小的优先。
        // 单个阶级承担的量不得超过它的现有数量，所以一轮可能补不完，需要多轮
        long missing = effective - assigned;
        if (missing > 0L) {
            List<Integer> order = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingLong((Integer i) -> -remainders[i])
                    .thenComparing(i -> ids.get(i)));
            while (missing > 0L) {
                boolean progressed = false;
                for (int idx = 0; idx < order.size() && missing > 0L; idx++) {
                    int i = order.get(idx);
                    long count = counts.get(ids.get(i));
                    if (shares[i] < count) {
                        shares[i]++;
                        missing--;
                        progressed = true;
                    }
                }
                if (!progressed) {
                    // 每一级都已经扣光了却还有缺口，而 effective = min(loss, total) ≤ total，
                    // 数学上不可能 —— 出现即说明上面的整数运算有误，
                    // 必须当场炸而不是少扣兵（少扣兵不会报错，只会让战损统计对不上）
                    throw new IllegalStateException("损失分摊没有分完：loss=" + loss
                            + ", total=" + total + ", effective=" + effective + ", 仍缺 " + missing);
                }
            }
        }

        Map<String, Long> out = new LinkedHashMap<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            if (shares[i] > 0L) {
                out.put(ids.get(i), shares[i]);
            }
        }
        return out;
    }
}
