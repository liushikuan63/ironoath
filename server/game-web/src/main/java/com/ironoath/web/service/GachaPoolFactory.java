package com.ironoath.web.service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.GachaCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.core.gacha.GachaEngine;
import com.ironoath.core.gacha.Tier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把 gacha 表 + hero 表组装成 {@link GachaEngine.Pool}，并暴露「公示概率」的读法。
 * 依赖：game-config（读表）、game-core（引擎数据结构）。
 *
 * <p><b>抽卡与概率公示必须共用这一份组装逻辑</b>，这是 B06 验收 3 的实现方式：
 * 「客户端概率面板数值与配置完全一致」不能靠两处各读一遍配置来保证 ——
 * 两处各读一遍，就存在「面板按 A 口径算、实际按 B 口径抽」的可能，
 * 而这个偏差不会报错，只会在玩家投诉「公示 2% 实际 1%」时才被发现。
 * 只有一个组装入口，面板与抽取就必然是同一份数字。
 *
 * <p><b>两组概率的分工必须记住</b>：
 * <ul>
 *   <li>{@code ssrChance/srChance/rChance/nChance} = <b>公示概率</b>（综合概率，含保底）。
 *       面板与合规核查用这一组。</li>
 *   <li>{@code ssrBaseChance/…} = <b>每抽基础概率</b>。引擎只用这一组。</li>
 * </ul>
 * 搞反的后果是综合概率偏离公示值约 0.5%（标准池实测 2.496% vs 公示 2%），
 * 既超出验收 1 的 0.3% 容差，也构成公示不实。校准见 tools/gacha-calibrate。
 */
@Component
public class GachaPoolFactory {

    /**
     * 限定池 SSR 中 UP 武将的占比。
     *
     * <p>TODO(需确认): 这个 50% 与「连续 2 次非 UP 则第 3 次 SSR 必为 UP」目前只写在
     * gacha 表的 disclosureText（公示文案）里，是自然语言而不是结构化字段。
     * 应当把它们加进 gacha 表（upSsrShare / upGuaranteeAfter），
     * 等 B15 商业化批次一并把卡池参数结构化 —— 引擎侧已经支持从 Pool 传入，届时只改这里。
     * 在此之前，本常量的值必须与 disclosureText 逐字核对，两者不一致就是公示不实。
     */
    public static final long UP_SSR_SHARE = FixedPoint.parse("0.50");
    public static final long UP_SSR_GUARANTEE_AFTER = 3L;

    private final ConfigRegistry configs;

    public GachaPoolFactory(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 组装引擎用的池子。四档用<b>基础概率</b>。 */
    public GachaEngine.Pool pool(GachaCfg cfg) {
        Map<Tier, List<String>> ids = new LinkedHashMap<>();
        Map<Tier, List<HeroCfg>> byTier = heroesByTier();
        for (Tier tier : Tier.values()) {
            List<String> list = new ArrayList<>();
            for (HeroCfg hero : byTier.get(tier)) {
                list.add(hero.id());
            }
            ids.put(tier, list);
        }
        boolean hasUp = cfg.upHeroId() != null;
        return new GachaEngine.Pool(cfg.id(),
                GachaEngine.chances(cfg.ssrBaseChance(), cfg.srBaseChance(),
                        cfg.rBaseChance(), cfg.nBaseChance()),
                cfg.ssrPity(), cfg.srPity(), ids,
                cfg.upHeroId(),
                hasUp ? UP_SSR_SHARE : 0L,
                hasUp ? UP_SSR_GUARANTEE_AFTER : 0L);
    }

    /** 按稀有度分组的武将名单。顺序即 hero 表声明顺序 —— 同档内等概率抽取，顺序必须稳定才可复现。 */
    public Map<Tier, List<HeroCfg>> heroesByTier() {
        Map<Tier, List<HeroCfg>> out = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            out.put(tier, new ArrayList<>());
        }
        for (HeroCfg hero : configs.all(HeroCfg.class)) {
            out.get(Tier.valueOf(hero.rarity().name())).add(hero);
        }
        return out;
    }

    /**
     * UP 武将自己所在的稀有度档位；无 UP 时返回 null。
     *
     * <p><b>不能写死 SSR</b>：新手池 UP 的是一名 SR（gacha 表该行的 why 写了理由）。
     * 写死 SSR 的后果是概率面板在 SSR 档里减掉一个不存在的 UP 名额、
     * 又在 SR 档里漏掉真实的 UP 加成，逐武将概率加总与档位公示值对不上 ——
     * 而 B06 验收 3 要求的正是「面板数值与配置完全一致」。
     */
    public Tier upTierOf(GachaCfg cfg) {
        if (cfg.upHeroId() == null) {
            return null;
        }
        for (Map.Entry<Tier, List<HeroCfg>> entry : heroesByTier().entrySet()) {
            for (HeroCfg hero : entry.getValue()) {
                if (hero.id().equals(cfg.upHeroId())) {
                    return entry.getKey();
                }
            }
        }
        throw new com.ironoath.config.ConfigException("卡池 " + cfg.id() + " 的 UP 武将 "
                + cfg.upHeroId() + " 不在 hero 表里，UP 永远不会兑现");
    }

    /** 公示概率（综合概率，含保底）。面板与合规核查都用这个。 */
    public static long disclosedRate(GachaCfg pool, Tier tier) {
        return switch (tier) {
            case SSR -> pool.ssrChance();
            case SR -> pool.srChance();
            case R -> pool.rChance();
            case N -> pool.nChance();
        };
    }

    /** 每抽基础概率。只有引擎用。 */
    public static long baseRate(GachaCfg pool, Tier tier) {
        return switch (tier) {
            case SSR -> pool.ssrBaseChance();
            case SR -> pool.srBaseChance();
            case R -> pool.rBaseChance();
            case N -> pool.nBaseChance();
        };
    }
}
