package com.ironoath.core.gacha;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.common.rng.Rng;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：抽卡引擎 —— 纯函数式的「池子 + 种子 + 保底计数 ⇒ 结果 + 新计数」（B06 §1/§6）。
 * 依赖：game-common 的 Rng 与 FixedPoint（零框架、零配置依赖，可脱离容器跑十万次模拟）。
 *
 * <p><b>概率口径：配置里的 ssrChance/srChance 是「公示概率（综合概率，含保底）」，
 * 而 ssrBaseChance/srBaseChance 才是「每抽基础概率」。引擎只用基础概率。</b>
 * 这不是多此一举：保底会凭空多造出一部分 SSR/SR，标准池公示 2%、80 抽保底，
 * 若每抽也按 2% 掷，实测综合概率会到 2.496%，高出公示值 0.496% ——
 * 既超出 B06 验收 1 的 0.3% 容差，也构成公示不实（B06 §6：不做完不许上线付费）。
 * 基础概率由 tools/gacha-calibrate 反解校准，使综合概率精确等于公示概率。
 *
 * <p><b>抽取算法（与校准脚本逐条对应，改一边必须改另一边）</b>：
 * <ol>
 *   <li>若 {@code ssrCounter + 1 >= ssrPity} → 本抽必为 SSR（保底）</li>
 *   <li>否则按四档基础概率掷一次得到档位；若档位是 R/N 且
 *       {@code srCounter + 1 >= srPity} → 升级为 SR（保底）</li>
 *   <li>计数：出 SSR 则两个计数都清零；出 SR 只清 srCounter；R/N 两个都 +1</li>
 * </ol>
 * 第 2 步的 SR 保底<b>只把 R/N 升级为 SR，绝不凭空造出 SSR</b> ——
 * 正是这个性质让 SSR 过程与 SR 过程解耦，SSR 的基础概率才有解析解。
 * 若改成「保底时重掷全池」，两个过程就耦合了，校准只能靠模拟，精度与可解释性都变差。
 *
 * <p><b>确定性</b>：每一抽用 {@code Rng.of(seed).fork(i)} 取独立子流，
 * 抽档位、选武将、判 UP 各自再 fork 一次。子流隔离的意义是：
 * 将来在某一步之间插入新的随机点，不会平移后续所有随机数的序列 ——
 * 否则加一个功能就会让全部历史战报/抽卡记录无法复现（B05 验收 2 的同一条纪律）。
 */
public final class GachaEngine {

    /** 抽取时用的定点分母区间：0 ~ 9999 恰好覆盖四位小数的概率。 */
    private static final long ROLL_SPAN = FixedPoint.SCALE;

    /**
     * 一个卡池的全部抽取参数。
     *
     * @param poolId          池 id（gacha 表主键）
     * @param baseChances     四档<b>基础</b>概率（定点），之和必须恰为 {@link FixedPoint#SCALE}
     * @param ssrPity         累计多少抽未出 SSR 则必出
     * @param srPity          累计多少抽未出 SR 及以上则必出 SR
     * @param heroesByTier    各档的武将 id 列表，<b>顺序即同档内的抽取顺序</b>，必须稳定
     * @param upHeroId        UP 武将 id；null 表示该池无 UP（标准池）。
     *                        <b>UP 档位由这个武将自己的稀有度决定</b>，不写死 SSR：
     *                        新手池 UP 的是一名 SR（gacha 表该行的 why 写了理由 ——
     *                        新手期 SR 够用，把 SSR 留到标准池，避免新手池一次性透支付费动机）。
     *                        写死 SSR 会让新手池的 UP 永远无法兑现，而且不会报错，
     *                        只会表现为「公示说 UP，玩家却永远抽不到 UP 武将」
     * @param upShareFixed    命中 UP 档位时，结果是 UP 武将的占比（0.5 ⇒ 5000）
     * @param upGuaranteeAfter 连续这么多次「命中 UP 档位但不是 UP 武将」之后，下一次必为 UP；
     *                        0 表示不启用（限定池公示文案里的「连续 2 次非 UP 则第 3 次必为 UP」⇒ 3）
     */
    public record Pool(String poolId,
                       Map<Tier, Long> baseChances,
                       long ssrPity,
                       long srPity,
                       Map<Tier, List<String>> heroesByTier,
                       String upHeroId,
                       long upShareFixed,
                       long upGuaranteeAfter) {

        public Pool {
            if (poolId == null || poolId.isBlank()) {
                throw new IllegalArgumentException("poolId 不得为空");
            }
            Map<Tier, Long> chances = new EnumMap<>(Tier.class);
            long total = 0L;
            for (Tier tier : Tier.values()) {
                Long fixed = baseChances == null ? null : baseChances.get(tier);
                if (fixed == null) {
                    throw new IllegalArgumentException("卡池 " + poolId + " 缺少 " + tier + " 档的基础概率");
                }
                if (fixed < 0L || fixed > FixedPoint.SCALE) {
                    throw new IllegalArgumentException("卡池 " + poolId + " 的 " + tier
                            + " 档概率越界：" + fixed);
                }
                chances.put(tier, fixed);
                total += fixed;
            }
            // 四档之和必须恰为 1（10000）。差 1 都会让最后一档的区间多吞或少吞一个整数，
            // 而这个偏差在十万次模拟里足以把综合概率推出 0.3% 容差
            if (total != FixedPoint.SCALE) {
                throw new IllegalArgumentException("卡池 " + poolId + " 四档基础概率之和必须恰为 "
                        + FixedPoint.SCALE + "（即 100%），实际=" + total
                        + "。公示概率与基础概率是两组数，别把公示值填进来 —— 校准见 tools/gacha-calibrate");
            }
            baseChances = Collections.unmodifiableMap(chances);

            if (ssrPity < 1 || srPity < 1) {
                throw new IllegalArgumentException("保底次数必须为正：ssrPity=" + ssrPity
                        + ", srPity=" + srPity);
            }
            if (srPity > ssrPity) {
                throw new IllegalArgumentException("SR 保底不得严于 SSR 保底（srPity=" + srPity
                        + " > ssrPity=" + ssrPity + "）：否则 SR 保底会在 SSR 保底之前触发，"
                        + "两条保底的语义就乱了");
            }
            Map<Tier, List<String>> heroes = new EnumMap<>(Tier.class);
            for (Tier tier : Tier.values()) {
                List<String> list = heroesByTier == null ? null : heroesByTier.get(tier);
                if (list == null || list.isEmpty()) {
                    throw new IllegalArgumentException("卡池 " + poolId + " 的 " + tier
                            + " 档没有任何武将，抽到该档时无从选择");
                }
                heroes.put(tier, List.copyOf(list));
            }
            heroesByTier = Collections.unmodifiableMap(heroes);

            if (upHeroId != null) {
                if (upShareFixed <= 0L || upShareFixed > FixedPoint.SCALE) {
                    throw new IllegalArgumentException("UP 占比必须落在 (0, 1] 的定点区间，实际="
                            + upShareFixed);
                }
                if (tierOf(heroesByTier, upHeroId) == null) {
                    throw new IllegalArgumentException("UP 武将 " + upHeroId
                            + " 不在本池任何一档的名单里，UP 永远不会兑现");
                }
            } else {
                // 无 UP 的池子不该带 UP 参数：留着会让「这池到底有没有 UP」变成需要读代码才能回答的问题
                if (upShareFixed != 0L || upGuaranteeAfter != 0L) {
                    throw new IllegalArgumentException("卡池 " + poolId
                            + " 没有 upHeroId，却配了 UP 参数（share=" + upShareFixed
                            + ", guaranteeAfter=" + upGuaranteeAfter + "）");
                }
            }
            if (upGuaranteeAfter < 0L) {
                throw new IllegalArgumentException("upGuaranteeAfter 不得为负：" + upGuaranteeAfter);
            }
        }

        public long baseChanceOf(Tier tier) {
            return baseChances.get(tier);
        }

        public List<String> heroesOf(Tier tier) {
            return heroesByTier.get(tier);
        }

        /** UP 武将所在的档位；无 UP 时为 null。 */
        public Tier upTier() {
            return upHeroId == null ? null : tierOf(heroesByTier, upHeroId);
        }

        private static Tier tierOf(Map<Tier, List<String>> heroes, String heroId) {
            for (Tier tier : Tier.values()) {
                if (heroes.get(tier).contains(heroId)) {
                    return tier;
                }
            }
            return null;
        }
    }

    /**
     * 保底计数器。
     *
     * @param ssr         距上次出 SSR 已累计多少抽
     * @param sr          距上次出 SR 及以上已累计多少抽
     * @param nonUpStreak 连续多少次「命中 UP 档位但不是 UP 武将」。
     *                    UP 档位由 upHeroId 自己所在的稀有度决定（见 {@link Pool}），
     *                    所以这个计数不绑定 SSR —— 新手池 UP 的是一名 SR
     */
    public record Counters(long ssr, long sr, long nonUpStreak) {

        public Counters {
            if (ssr < 0L || sr < 0L || nonUpStreak < 0L) {
                throw new IllegalArgumentException("保底计数不得为负：ssr=" + ssr
                        + ", sr=" + sr + ", nonUpStreak=" + nonUpStreak);
            }
        }

        public static Counters fresh() {
            return new Counters(0L, 0L, 0L);
        }
    }

    /**
     * 一次抽取的结果。
     *
     * @param pity 本次是否由保底触发。<b>合规必需字段</b>（B06 禁止项：抽卡日志不要缺 isPity，
     *             监管要能区分「正常抽到」与「保底触发」）
     */
    public record Draw(String heroId, Tier tier, boolean pity) {

        public Draw {
            if (heroId == null || heroId.isBlank()) {
                throw new IllegalArgumentException("heroId 不得为空");
            }
            if (tier == null) {
                throw new IllegalArgumentException("tier 不得为 null");
            }
        }
    }

    /** 一批抽取的结果与新的保底计数。 */
    public record Batch(List<Draw> draws, Counters counters) {

        public Batch {
            draws = List.copyOf(draws);
        }
    }

    private GachaEngine() {
    }

    /**
     * 抽 count 次。
     *
     * @param pool  卡池参数
     * @param seed  本次十连/单抽的种子。<b>同一 seed + 同一 count + 同一 counters ⇒ 逐条相同</b>
     *              （B06 验收 11）。种子必须由服务端生成，不能来自任何请求字段，
     *              否则玩家可以离线枚举出哪个种子出 SSR 再拿它发请求
     * @param count 抽取次数，必须为正
     * @param start 起始保底计数
     */
    public static Batch draw(Pool pool, long seed, int count, Counters start) {
        if (pool == null) {
            throw new IllegalArgumentException("pool 不得为 null");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("抽取次数必须为正，实际=" + count);
        }
        if (start == null) {
            throw new IllegalArgumentException("start 不得为 null，无保底进度请用 Counters.fresh()");
        }
        List<Draw> draws = new ArrayList<>(count);
        long ssr = start.ssr();
        long sr = start.sr();
        long nonUpStreak = start.nonUpStreak();
        Tier upTier = pool.upTier();

        for (int i = 0; i < count; i++) {
            // 每抽一条独立子流：抽数不同不会改变前面各抽的结果
            Rng stream = Rng.of(seed).fork(i);
            Tier tier;
            boolean pity;
            if (ssr + 1 >= pool.ssrPity()) {
                tier = Tier.SSR;
                pity = true;
            } else {
                tier = rollTier(pool, stream.fork(1));
                pity = false;
                if ((tier == Tier.R || tier == Tier.N) && sr + 1 >= pool.srPity()) {
                    tier = Tier.SR;
                    pity = true;
                }
            }
            String heroId = pickHero(pool, tier, stream.fork(2), nonUpStreak);

            // UP 连击按「命中 UP 档位」记，而不是按 SSR 记：新手池的 UP 武将是一名 SR
            if (upTier != null && tier == upTier) {
                nonUpStreak = heroId.equals(pool.upHeroId()) ? 0L : nonUpStreak + 1;
            }
            if (tier == Tier.SSR) {
                ssr = 0L;
                sr = 0L;
            } else if (tier == Tier.SR) {
                sr = 0L;
                ssr++;
            } else {
                sr++;
                ssr++;
            }
            draws.add(new Draw(heroId, tier, pity));
        }
        return new Batch(draws, new Counters(ssr, sr, nonUpStreak));
    }

    /** 按四档基础概率掷档位。区间是左闭右开的累加区间，顺序即 {@link Tier} 的声明顺序。 */
    private static Tier rollTier(Pool pool, Rng rng) {
        long roll = rng.range(0L, ROLL_SPAN - 1L);
        long acc = 0L;
        for (Tier tier : Tier.values()) {
            acc += pool.baseChanceOf(tier);
            if (roll < acc) {
                return tier;
            }
        }
        // 四档之和恰为 SCALE 由 Pool 构造器保证，所以这里只可能是 roll == SCALE-1 且累加到顶
        return Tier.N;
    }

    /**
     * 在档位内选一名武将。
     *
     * <p>命中 UP 档位时分两步：先判是否 UP（占比 upShareFixed），
     * 若连续 upGuaranteeAfter-1 次都不是 UP，则本次必为 UP（大保底）。
     * 大保底是 gacha 表公示文案里明写的承诺，不兑现就是公示不实。
     */
    private static String pickHero(Pool pool, Tier tier, Rng rng, long nonUpStreak) {
        List<String> candidates = pool.heroesOf(tier);
        if (tier != pool.upTier() || pool.upHeroId() == null) {
            return candidates.get((int) rng.range(0L, candidates.size() - 1L));
        }
        boolean forceUp = pool.upGuaranteeAfter() > 0L
                && nonUpStreak + 1 >= pool.upGuaranteeAfter();
        boolean hitUp = forceUp || rng.chance(pool.upShareFixed());
        if (hitUp) {
            return pool.upHeroId();
        }
        // 非 UP 的命中在「除 UP 之外的同档武将」里等概率选。
        // 用 LinkedHashMap 保序，保证同 seed 下选择结果稳定（EnumMap/HashMap 的迭代顺序不可依赖）
        List<String> others = new ArrayList<>(candidates.size() - 1);
        for (String id : candidates) {
            if (!id.equals(pool.upHeroId())) {
                others.add(id);
            }
        }
        if (others.isEmpty()) {
            // 该档只有 UP 武将一个人：那「非 UP」这个分支根本不该走到，直接返回 UP
            return pool.upHeroId();
        }
        return others.get((int) rng.range(0L, others.size() - 1L));
    }

    /**
     * 构造一个「各档武将名单」的辅助方法，保证四个档都齐全且顺序稳定。
     *
     * <p>放在引擎里而不是调用方，是因为漏掉一个档会在 {@link Pool} 构造期才报错，
     * 而那时已经脱离了「谁漏的」这个上下文。
     */
    public static Map<Tier, List<String>> heroes(List<String> ssr, List<String> sr,
                                                 List<String> r, List<String> n) {
        Map<Tier, List<String>> map = new LinkedHashMap<>();
        map.put(Tier.SSR, ssr);
        map.put(Tier.SR, sr);
        map.put(Tier.R, r);
        map.put(Tier.N, n);
        return map;
    }

    /** 构造四档基础概率，之和必须恰为 10000。 */
    public static Map<Tier, Long> chances(long ssr, long sr, long r, long n) {
        Map<Tier, Long> map = new EnumMap<>(Tier.class);
        map.put(Tier.SSR, ssr);
        map.put(Tier.SR, sr);
        map.put(Tier.R, r);
        map.put(Tier.N, n);
        return map;
    }
}
