package com.ironoath.web.reward;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;

/**
 * 职责：把「武将碎片」与「整卡武将」这两类非资源非道具的奖励接到真实存储
 * （B06 落地后替换 UnsupportedExtras 的对应分支）。
 * 依赖：game-config（查武将稀有度）、{@link RewardPorts.Bag}（碎片本质是道具）、
 *       {@link HeroRepository}（整卡要写进武将册）。
 *
 * <p><b>碎片的 id 是武将 id，但落库落到「按稀有度的碎片道具」上</b>
 * （{@code item_mat_hero_frag_ssr} 这类）。理由：item 表里只有按稀有度的碎片道具，
 * 没有「裴惊澜的碎片」这种道具 —— 因为 B06 §1 的碎片经济（hero_rarity 表的
 * dupFragment / composeFragment / starUpFragment）全部是按稀有度定的。
 * 所以 HERO_FRAGMENT(id=hero_ssr_01, count=30) 的实际效果是「SSR 碎片 ×30」。
 *
 * <p>这个映射必须显式写在这里而不是让调用方自己转：调用方（抽卡、活动、邮件补发）
 * 只应该知道「我要发某武将的碎片」，不该各自去查稀有度再拼道具 id ——
 * 三个地方各拼一次，迟早有一处拼错，而拼错的表现是玩家拿到了另一档的碎片。
 *
 * <p><b>整卡（HERO）走的是另一条路</b>：它写进武将册而不是背包（唯一、不可堆叠），
 * 所以它必须在这里拿到 {@link HeroRepository}。重复获得时按 B06 的重复经济
 * <b>转成该稀有度的碎片</b>，而不是静默丢弃 —— 丢弃会让玩家看到「获得 ×1」
 * 而武将册没有任何变化（他已经有了），那正是 B06 要防的那种「假发放」。
 *
 * <p>体力（STAMINA）仍然抛异常，由 B09 落地；<b>特权（PRIVILEGE）自 B19 起转给
 * {@link PaidPrivilegeGrants}</b> —— 它是唯一会写玩家付费权益的发放通路。
 * 未实现的奖励类型必须<b>响亮地失败</b>并进补偿队列，静默返回「发放成功」
 * 会让玩家看到「已获得体力 ×20」而账户里什么都没有。
 */
public final class HeroFragmentExtras implements RewardPorts.Extras {

    private final RewardPorts.Bag bag;
    private final ConfigRegistry configs;
    /** 整卡要写进武将册；碎片那条路不用它。 */
    private final HeroRepository heroes;
    /** 特权（月卡延期 / 基金登记 / 首充登记）交给它，本类不自己碰玩家存档。 */
    private final PaidPrivilegeGrants privileges;

    public HeroFragmentExtras(RewardPorts.Bag bag, ConfigRegistry configs, HeroRepository heroes,
                              PaidPrivilegeGrants privileges) {
        if (bag == null || configs == null || heroes == null || privileges == null) {
            throw new IllegalArgumentException(
                    "Bag / ConfigRegistry / HeroRepository / PaidPrivilegeGrants 都不得为 null");
        }
        this.bag = bag;
        this.configs = configs;
        this.heroes = heroes;
        this.privileges = privileges;
    }

    @Override
    public long grant(String playerId, RewardType type, String id, long count, long now) {
        if (type == RewardType.HERO) {
            return grantHero(playerId, id, count);
        }
        if (type == RewardType.PRIVILEGE) {
            // 特权不在本类的职责里（它写的不是册子也不是背包），但路由在这里：
            // "哪一类奖励归谁"只需要看这一处
            return privileges.grant(playerId, id, count, now);
        }
        if (type != RewardType.HERO_FRAGMENT) {
            // 交给 TransientRewardPorts.UnsupportedExtras 的同一套语义：抛出去，
            // 让发放器写日志并进补偿队列
            throw new UnsupportedOperationException("奖励类型 " + type + "（id=" + id + "）尚未落地："
                    + switch (type) {
                        case STAMINA -> "由 B09 PVE 与关卡内容实现";
                        case PRIVILEGE -> "不应走到这里（B19 已接 PaidPrivilegeGrants）";
                        case HERO -> "不应走到这里";
                        case HERO_FRAGMENT -> "不应走到这里";
                        case RESOURCE, ITEM -> "不应走到 Extras，应由 Wallet / Bag 处理";
                    });
        }
        if (count <= 0L) {
            throw new IllegalArgumentException("发放数量必须为正：id=" + id + ", count=" + count);
        }
        String itemId = fragmentItemOf(id);
        return bag.add(playerId, itemId, count);
    }

    /**
     * 发放整卡武将（B06 §1「主线赠送：首日必得 1 名 SR」）。
     *
     * <p><b>重复获得转碎片、不静默丢弃</b>：与抽卡的重复经济同一条口径
     * （{@code hero_rarity.dupFragment}）。丢弃会让玩家看到「获得 ×1」而武将册毫无变化
     * —— 那正是「假发放」，比发少更糟。转出去的碎片仍走本类的 {@code bag.add}，
     * 所以堆叠上限与背包容量由同一条路径保证。
     *
     * <p><b>只支持 count == 1</b>：整卡是唯一对象，发两张同名武将在语义上不存在 ——
     * 真出现 count=2 时应当是把重复折算写错了，宁可响。
     *
     * @return 实际发放数（1 = 真的新获得；0 = 已有该武将，此时已按档折算碎片）
     */
    private long grantHero(String playerId, String heroId, long count) {
        if (count != 1L) {
            throw new IllegalArgumentException("整卡武将一次只能发 1 名（id=" + heroId
                    + ", count=" + count + "）：重复折算请显式发 HERO_FRAGMENT，"
                    + "否则「发 2 个」到底该给碎片还是给第二名同名武将无从判断");
        }
        HeroCfg hero = requireHero(heroId);
        // 建档按 GachaAppService.loadOrCreateRoster 的同一套动作顺序：先查 → 没有再 insert → 重读。
        // **顺序不能反**：versionOf 在档不存在时直接抛（「武将存档不存在：playerId=…」），
        // 所以它必须发生在建档之后 —— 真机实测：把它放在建档之前，玩家领奖时会看到
        // 「领取成功」而武将进了补偿队列（回执非空、册子空着，是最难自助排查的一类失败）
        HeroRoster roster = loadOrCreateRoster(playerId);
        long version = heroes.versionOf(playerId);
        if (roster.obtain(heroId)) {
            heroes.save(playerId, roster, version);
            return 1L;
        }
        // 已经有了：按该档的 dupFragment 折成碎片（与抽卡重复获得同一条口径）
        long fragments = dupFragmentOf(hero);
        if (fragments > 0L) {
            bag.add(playerId, fragmentItemOf(heroId), fragments);
        }
        return 0L;
    }

    /** 取或建武将册（与 {@code GachaAppService.loadOrCreateRoster} 同一条动作顺序）。 */
    private HeroRoster loadOrCreateRoster(String playerId) {
        var existing = heroes.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        heroes.insertIfAbsent(playerId, new HeroRoster());
        return heroes.findByPlayerId(playerId).orElseThrow(() -> new IllegalStateException(
                "武将存档创建后立即读不到：playerId=" + playerId
                        + "（发放整卡武将依赖它，读不到就没法把武将写进册）"));
    }

    private HeroCfg requireHero(String heroId) {
        try {
            return configs.get(HeroCfg.class, heroId);
        } catch (ConfigException e) {
            throw new IllegalArgumentException("HERO 的 id 必须是 hero 表的行 id，实际=" + heroId);
        }
    }

    /** 重复武将折算几个碎片：{@code hero_rarity.dupFragment}（与抽卡那条路同一个数据源）。 */
    private long dupFragmentOf(HeroCfg hero) {
        try {
            return configs.get(com.ironoath.config.cfg.HeroRarityCfg.class,
                    hero.rarity().name()).dupFragment();
        } catch (ConfigException e) {
            throw new IllegalStateException("hero_rarity 表缺少 " + hero.rarity()
                    + " 档，重复武将无法折算碎片（武将 " + hero.id() + "）");
        }
    }

    /** 武将 id → 该武将稀有度对应的碎片道具 id。 */
    public String fragmentItemOf(String heroId) {
        HeroCfg hero;
        try {
            hero = configs.get(HeroCfg.class, heroId);
        } catch (ConfigException e) {
            throw new IllegalArgumentException("HERO_FRAGMENT 的 id 必须是 hero 表的行 id，实际="
                    + heroId + "。这个映射错了不会报错，只会让玩家拿到另一档的碎片");
        }
        String itemId = "item_mat_hero_frag_" + hero.rarity().name().toLowerCase(java.util.Locale.ROOT);
        if (!configs.rawTable("item").has(itemId)) {
            throw new IllegalStateException("item 表里缺少 " + hero.rarity()
                    + " 档的碎片道具 " + itemId + "，武将 " + heroId + " 的碎片无处安放");
        }
        return itemId;
    }
}
