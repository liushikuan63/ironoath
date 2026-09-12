package com.ironoath.core.chest;

import com.ironoath.common.rng.Rng;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：宝箱开启器 —— 服务端 PRNG + seed，结果可复现（B04 §4、验收 4）。
 * 依赖：game-common 的 Rng、core.reward 的 RewardItem（纯 Java，零框架）。
 *
 * <p><b>绝不在客户端开箱</b>（B04 禁止项、铁律 3）：客户端只播放服务端下发的结果。
 * 客户端开箱再上报等于把掉落率交给玩家改，而且改包成本极低。
 *
 * <p>可复现性靠两条：
 * <ol>
 *   <li>随机只走 {@link Rng}，禁止 Math.random（B04 禁止项）</li>
 *   <li>第 i 次抽取用 {@code rng.fork(i)} 派生独立子流 —— 这样「批量开 100 个」与
 *       「逐个开 100 次（seed 递增）」不会互相影响，且新增一种掉落物不会移动已有抽取的序列</li>
 * </ol>
 *
 * <p>批量开箱（B04 验收 3：一次开 100 个只发 1 次请求）在本类内部循环完成，
 * 结果按掉落表声明顺序聚合，因此客户端拿到的是「稀有度聚合后的清单」而不是 100 条流水。
 */
public final class ChestOpener {

    /**
     * 一条掉落项。
     *
     * @param rewardId 奖励 id（资源 id / 道具 id / 稀有度）
     * @param type     奖励类型
     * @param weight   权重，必须为正。概率 = weight / 总权重，不直接写概率是为了
     *                 让策划调一个数时不必同时改其它数保证总和为 1
     * @param count    单次掉落数量
     * @param rare     是否属于「稀有及以上」，保底机制只认这个标记
     */
    public record Drop(String rewardId, RewardType type, long weight, long count, boolean rare) {

        public Drop {
            if (rewardId == null || rewardId.isBlank()) {
                throw new IllegalArgumentException("掉落项 rewardId 不得为空");
            }
            if (type == null) {
                throw new IllegalArgumentException("掉落项 type 不得为 null，rewardId=" + rewardId);
            }
            if (weight <= 0L) {
                throw new IllegalArgumentException("权重必须为正，rewardId=" + rewardId + ", weight=" + weight);
            }
            if (count <= 0L) {
                throw new IllegalArgumentException("数量必须为正，rewardId=" + rewardId + ", count=" + count);
            }
        }
    }

    /**
     * 一个宝箱的掉落表。
     *
     * @param chestId       宝箱 id，对应 item 表的行 id
     * @param drops         掉落项，<b>顺序即聚合顺序</b>，因此必须稳定（用配置表声明顺序）
     * @param pityThreshold 保底阈值：连续这么多次没出稀有，第 N 次必出稀有。
     *                      0 表示不启用保底
     */
    public record Table(String chestId, List<Drop> drops, long pityThreshold) {

        public Table {
            if (chestId == null || chestId.isBlank()) {
                throw new IllegalArgumentException("chestId 不得为空");
            }
            drops = List.copyOf(drops);
            if (drops.isEmpty()) {
                throw new IllegalArgumentException("宝箱 " + chestId + " 的掉落表不得为空");
            }
            if (pityThreshold < 0L) {
                throw new IllegalArgumentException("pityThreshold 不得为负：" + pityThreshold);
            }
            // 配了保底却没有任何稀有项，是配置错误而不是运行时意外：
            // 必须在构造期（配置加载时）就失败，而不是等到玩家第 N 次开箱时才炸
            if (pityThreshold > 0L && drops.stream().noneMatch(Drop::rare)) {
                throw new IllegalStateException("宝箱 " + chestId + " 配置了保底阈值 " + pityThreshold
                        + " 但掉落表里没有任何 rare 项，保底永远无法兑现");
            }
        }

        long totalWeight() {
            long total = 0L;
            for (Drop d : drops) {
                total += d.weight();
            }
            return total;
        }

        List<Drop> rareDrops() {
            return drops.stream().filter(Drop::rare).toList();
        }
    }

    private ChestOpener() {
    }

    /**
     * 开一个宝箱。
     *
     * @param table    掉落表
     * @param seed     本场开箱的种子
     * @param drawIndex 第几次抽取（从 0 开始），用于 fork 出独立子流
     * @param sinceLastRare 距上次出稀有已经过了多少次抽取，用于保底判定
     * @return 抽中的掉落项
     */
    public static Drop drawOnce(Table table, long seed, int drawIndex, long sinceLastRare) {
        if (drawIndex < 0) {
            throw new IllegalArgumentException("drawIndex 不得为负：" + drawIndex);
        }
        // 保底优先于随机：达到阈值时直接从稀有池里抽，不再走全池
        if (table.pityThreshold() > 0L && sinceLastRare + 1 >= table.pityThreshold()) {
            // rareDrops 非空由 Table 构造器保证，这里不必重复校验
            return pickWeighted(table.rareDrops(), Rng.of(seed).fork(drawIndex));
        }
        return pickWeighted(table.drops(), Rng.of(seed).fork(drawIndex));
    }

    /**
     * 批量开箱并按掉落表声明顺序聚合（B04 验收 3：一次开 100 个只发 1 次请求）。
     *
     * @param table 掉落表
     * @param seed  本次批量开箱的种子。同一 seed + 同一 count ⇒ 逐条相同的结果（验收 4）
     * @param count 开箱数量，必须为正
     * @return 聚合后的奖励清单，顺序与掉落表声明顺序一致
     */
    public static List<RewardItem> openBatch(Table table, long seed, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("开箱数量必须为正，实际=" + count);
        }
        // 聚合用 LinkedHashMap 保持掉落表声明顺序：客户端按稀有度展示时需要稳定顺序，
        // 用 HashMap 会让同一批奖励每次展示的排列都不同
        Map<String, Long> aggregated = new LinkedHashMap<>();
        Map<String, RewardItem> prototypes = new LinkedHashMap<>();
        for (Drop d : table.drops()) {
            aggregated.put(d.rewardId(), 0L);
            prototypes.put(d.rewardId(), new RewardItem(d.type(), d.rewardId(), 1L));
        }

        long sinceLastRare = 0L;
        for (int i = 0; i < count; i++) {
            Drop hit = drawOnce(table, seed, i, sinceLastRare);
            aggregated.merge(hit.rewardId(), hit.count(), Long::sum);
            sinceLastRare = hit.rare() ? 0L : sinceLastRare + 1;
        }

        List<RewardItem> result = new ArrayList<>();
        for (Map.Entry<String, Long> e : aggregated.entrySet()) {
            if (e.getValue() > 0L) {
                result.add(prototypes.get(e.getKey()).withCount(e.getValue()));
            }
        }
        return result;
    }

    /** 按权重抽取。用 Rng 的无偏区间随机，不用 next() × 总权重（后者在大权重下分布不均）。 */
    private static Drop pickWeighted(List<Drop> pool, Rng rng) {
        long total = 0L;
        for (Drop d : pool) {
            total += d.weight();
        }
        // nextInt 是半开区间 [0, total)，与权重和的取值范围一致
        long roll = rng.range(0L, total - 1L);
        long acc = 0L;
        for (Drop d : pool) {
            acc += d.weight();
            if (roll < acc) {
                return d;
            }
        }
        // 理论上不可达：roll < total 且 acc 最终等于 total
        throw new IllegalStateException("权重抽取越界，roll=" + roll + ", total=" + total);
    }
}
