package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.log.TraceContext;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.ConfigException;
import com.ironoath.config.cfg.EquipCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.HeroRarityCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.config.cfg.SkillCfg;
import com.ironoath.core.bag.Inventory;
import com.ironoath.core.bag.InventoryRepository;
import com.ironoath.core.hero.EquipSlot;
import com.ironoath.core.hero.HeroAttrs;
import com.ironoath.core.hero.HeroCalculator;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRepository;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.core.hero.HeroRules;
import com.ironoath.core.hero.Lineup;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.web.dto.generated.AttrTriple;
import com.ironoath.web.dto.generated.BonusBreak;
import com.ironoath.web.dto.generated.BonusZone;
import com.ironoath.web.dto.generated.ComposeCandidate;
import com.ironoath.web.dto.generated.FragmentView;
import com.ironoath.web.dto.generated.HeroBonus;
import com.ironoath.web.dto.generated.HeroEquipReq;
import com.ironoath.web.hero.EquipLedger;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.web.hero.EquipWearer;
import com.ironoath.web.dto.generated.HeroGrowResp;
import com.ironoath.web.dto.generated.HeroIdReq;
import com.ironoath.web.dto.generated.HeroItemReq;
import com.ironoath.web.dto.generated.HeroLevelUpReq;
import com.ironoath.web.dto.generated.HeroListResp;
import com.ironoath.web.dto.generated.HeroRarity;
import com.ironoath.web.dto.generated.HeroView;
import com.ironoath.web.dto.generated.ItemCount;
import com.ironoath.web.dto.generated.LineupView;
import com.ironoath.web.dto.generated.SetLineupReq;
import com.ironoath.web.dto.generated.SetLineupResp;
import com.ironoath.web.dto.generated.WornEquip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：武将应用服务 —— 五条养成线 + 编队（B06 §2/§4）。
 * 依赖：game-core（武将聚合与计算）、game-config（配置）、game-web 的锁/幂等/背包端口。
 *
 * <p>与城建、背包同一套并发纪律：<b>requestId 幂等 → 玩家锁 → 乐观锁版本</b>，三道缺一不可。
 * 养成操作全是「读档 → 改 → 落库」，没有锁就会被并发覆盖；
 * 只有锁没有幂等，断网重放会让玩家白喂一次经验书。
 *
 * <p><b>材料消耗一律走 {@code RewardPorts.Bag}</b>，不自己持有背包副本 ——
 * 这是 B04 修掉的那个 bug 的同一条纪律：副本 + 旧版本号在任何一次发奖之后都会过期，
 * 轻则乐观锁冲突，重则把刚发出去的东西整份覆盖掉。
 *
 * <p><b>顺序恒为「先扣材料再改状态，改失败就退还」</b>：反过来在材料不足时会让玩家白拿一次养成，
 * 那是可以直接刷的漏洞；而先扣的唯一风险是「扣了但状态没改」，用 try/catch 退还即可闭合。
 */
@Service
public class HeroAppService {

    private static final Logger LOG = LoggerFactory.getLogger(HeroAppService.class);

    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final HeroRepository heroes;
    private final HeroStatsService stats;
    private final RewardPorts.Bag bagPort;
    private final EquipLedgers equipLedgers;
    private final EquipWearer wearer;
    private final InventoryRepository inventories;
    private final PlayerLock playerLock;
    private final IdempotencyStore idempotency;
    private final TimeService timeService;

    public HeroAppService(ConfigRegistry configs, HeroRepository heroes, HeroStatsService stats,
                          RewardPorts.Bag bagPort, EquipLedgers equipLedgers, EquipWearer wearer,
                          InventoryRepository inventories, PlayerLock playerLock,
                          IdempotencyStore idempotency, TimeService timeService) {
        this.configs = configs;
        this.heroes = heroes;
        this.stats = stats;
        this.bagPort = bagPort;
        this.equipLedgers = equipLedgers;
        this.wearer = wearer;
        this.inventories = inventories;
        this.playerLock = playerLock;
        this.idempotency = idempotency;
        this.timeService = timeService;
    }

    // ---------- 查询 ----------

    /** 武将总览：全部武将 + 三套编队 + 各稀有度碎片余额 + 带兵上限。 */
    public HeroListResp list(String playerId) {
        long now = timeService.serverNow();
        HeroRoster roster = loadOrCreate(playerId);
        HeroRules rules = stats.rules();
        // 一份装备快照服务整个响应：几名武将 × 四个槽位都从它读，读一次背包而不是十几次的量
        EquipLedger equips = equipLedgers.of(playerId);
        List<HeroView> views = new ArrayList<>();
        for (HeroInstance instance : roster.heroes()) {
            views.add(toView(equips, instance));
        }
        List<LineupView> lineups = new ArrayList<>();
        for (Lineup lineup : roster.lineups(rules)) {
            lineups.add(toLineupView(equips, lineup, roster));
        }
        return new HeroListResp(views, lineups, fragmentBalancesOf(playerId, roster),
                stats.troopCap(equips, roster, 0), 0L, now);
    }

    /**
     * 当前带兵上限（B05 §二：人口/统帅上限 = Σ武将统帅值 + 科技加成）。
     *
     * <p>供军队系统（训练时校验上限）与后续的行军系统使用。用第 0 套编队预设 ——
     * 「当前生效的编队」这个概念要到 B07 行军落地时才需要按出征队伍区分，
     * 那时改成传入 presetIndex 即可，调用方不必改。
     */
    public long troopCap(String playerId) {
        return stats.troopCap(equipLedgers.of(playerId), loadOrCreate(playerId), 0);
    }

    // ---------- 养成五条线 ----------

    /** 等级线：投喂经验书，一次可能连升数级。 */
    public HeroGrowResp levelUp(String playerId, HeroLevelUpReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        if (req.expItems() == null || req.expItems().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "expItems 不得为空");
        }
        return guarded(playerId, req.requestId(), (roster, version) -> {
            HeroInstance instance = requireHero(roster, req.heroId());
            HeroCfg cfg = stats.heroCfg(req.heroId());

            // 先把所有经验书一次性扣掉，再统一投喂：逐本扣会让「扣到第 3 本时失败」
            // 留下一个已扣 2 本却没升级的中间态
            long totalExp = 0L;
            List<ItemCount> consumed = new ArrayList<>();
            for (ItemCount item : req.expItems()) {
                ItemCfg itemCfg = requireItem(item.itemId());
                if (itemCfg.effectKind() != ItemCfg.EffectKind.GRANT_HERO_EXP) {
                    throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                            "道具 " + item.itemId() + " 不是经验书（effectKind="
                                    + itemCfg.effectKind() + "）");
                }
                if (item.count() <= 0L) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "道具数量必须为正：" + item.itemId() + " × " + item.count());
                }
                consumeItem(playerId, item.itemId(), item.count(), itemCfg.name());
                consumed.add(item);
                totalExp += itemCfg.effectValue() * item.count();
            }

            int before = instance.level();
            int gained;
            try {
                gained = instance.feedExp(totalExp, (int) cfg.maxLevel(), stats.rules());
            } catch (RuntimeException e) {
                refundAll(consumed, playerId);
                throw e;
            }
            save(playerId, roster, version);
            LOG.info("武将升级 playerId={} hero={} {}→{} 级 消耗经验={} 道具={}",
                    playerId, req.heroId(), before, instance.level(), totalExp, consumed);
            return new HeroGrowResp(toView(equipLedgers.of(playerId), instance),
                    consumed, timeService.serverNow());
        });
    }

    /** 星级线：消耗该稀有度的碎片升一星。 */
    public HeroGrowResp starUp(String playerId, HeroIdReq req) {
        return growByFragment(playerId, req);
    }

    /** 碎片合成：凑够 hero_rarity.composeFragment 就获得该武将。 */
    public HeroGrowResp compose(String playerId, HeroIdReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        HeroCfg cfg = requireHeroCfg(req.heroId());
        long need = rarityEconomy(cfg.rarity()).composeFragment();
        String fragmentItem = stats.fragmentItemId(cfg.rarity());
        ItemCfg fragmentCfg = requireItem(fragmentItem);

        return guarded(playerId, req.requestId(), (roster, version) -> {
            if (roster.owns(req.heroId())) {
                // 已拥有还能「合成」的话，玩家就能靠反复合成把碎片刷成无限武将
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "已拥有武将 " + cfg.name() + "，不能重复合成；重复获得会转成碎片");
            }
            consumeItem(playerId, fragmentItem, need, fragmentCfg.name());
            try {
                roster.obtain(req.heroId());
            } catch (RuntimeException e) {
                bagPort.add(playerId, fragmentItem, need);
                throw e;
            }
            save(playerId, roster, version);
            LOG.info("碎片合成武将 playerId={} hero={} 消耗碎片={}×{}",
                    playerId, req.heroId(), fragmentItem, need);
            return new HeroGrowResp(toView(equipLedgers.of(playerId), roster.hero(req.heroId())),
                    List.of(new ItemCount(fragmentItem, need)), timeService.serverNow());
        });
    }

    /** 觉醒线：消耗觉醒石推进一阶。 */
    public HeroGrowResp awaken(String playerId, HeroItemReq req) {
        return growByItem(playerId, req, ItemCfg.EffectKind.AWAKEN_HERO, "awaken");
    }

    /** 技能线：消耗技能书升主技能或副技能。 */
    public HeroGrowResp skillUp(String playerId, HeroItemReq req) {
        return growByItem(playerId, req, ItemCfg.EffectKind.UP_HERO_SKILL, "skillUp");
    }

    /**
     * 装备线：穿或卸。
     *
     * <p><b>穿上的不是"一行装备"而是"某一件"</b>（§五⑤）：请求带的是实例 uid，
     * 换下来与穿上去的那两件都只是把 {@code worn} 翻一下 —— 实例永不出账本，
     * 因为它带着强化等级，那是一件装备的财产。旧写法（从背包扣一个数量、卸下时再加回去）
     * 因此整段消失，连带消失的是"换装时扣了又退"那两处回滚分支。
     *
     * <p><b>老客户端传的是配置行 id</b>，那条路今天仍然通（按"该行的第一件未穿"解析并记 ERROR），
     * 因为界面还列不出"件"。等客户端能列实例了，把那条解析与 {@code firstUnwornEquip} 一起删掉。
     */
    public HeroGrowResp equip(String playerId, HeroEquipReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        if (req.slot() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "slot 不得为空");
        }
        return guarded(playerId, req.requestId(), (roster, version) -> {
            HeroInstance instance = requireHero(roster, req.heroId());
            EquipSlot slot = EquipSlot.valueOf(req.slot().name());

            if (req.equipUid() == null) {
                String removed = instance.equip(slot, null);
                if (removed != null) {
                    // 先让背包把格子腾出来（放不下会抛），再落武将的档：
                    // 顺序反过来会留下"槽位空了，而那件东西既不算穿着也占不到格子"的中间态
                    wearer.unwear(playerId, removed);
                }
                save(playerId, roster, version);
                LOG.info("卸下装备 playerId={} hero={} slot={} uid={}",
                        playerId, req.heroId(), slot, removed);
                return new HeroGrowResp(toView(equipLedgers.of(playerId), instance),
                        List.of(), timeService.serverNow());
            }

            String uid = resolveWearableUid(playerId, req.equipUid());
            Inventory.EquipInstance target = wearer.require(playerId, uid);
            EquipCfg equipCfg = requireEquip(target.equipId());
            if (equipCfg.slot() != EquipCfg.Slot.valueOf(slot.name())) {
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "装备 " + equipCfg.name() + " 是 " + equipCfg.slot() + " 槽位的，"
                                + "不能穿到 " + slot + " 槽");
            }
            if (instance.level() < equipCfg.requireLevel()) {
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "需要武将 " + equipCfg.requireLevel() + " 级才能穿 " + equipCfg.name()
                                + "，当前 " + instance.level() + " 级");
            }
            String replaced = instance.equipOf(slot);
            if (uid.equals(replaced)) {
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        "这件装备（uid=" + uid + "）已经穿在 " + slot + " 槽上了，不用重复穿");
            }
            // 两件的状态在一次落库里换完；这一步之后武将的档才动，
            // 所以"背包说它穿着、档说没穿"最多只在异常路径上短暂存在（记在 EquipWearer 的注释里）
            wearer.wear(playerId, uid, replaced);
            instance.equip(slot, uid);
            save(playerId, roster, version);
            LOG.info("穿装备 playerId={} hero={} slot={} uid={} 行={} 换下={}",
                    playerId, req.heroId(), slot, uid, target.equipId(), replaced);
            return new HeroGrowResp(toView(equipLedgers.of(playerId), instance),
                    List.of(new ItemCount(target.equipId(), 1L)), timeService.serverNow());
        });
    }

    /** 编队：整体替换一套预设（B06 验收 5「下阵无残留」由这个语义保证）。 */
    public SetLineupResp setLineup(String playerId, SetLineupReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            acquireIdempotency(req.requestId(), playerId);
            try {
                HeroRoster roster = loadOrCreate(playerId);
                long version = heroes.versionOf(playerId);
                HeroRules rules = stats.rules();
                try {
                    // 副将位为空用 null 表示，而 List.of 拒绝 null 元素，必须用 Arrays.asList
                    roster.setLineup(req.presetIndex(), req.main(),
                            java.util.Arrays.asList(req.sub1(), req.sub2()), rules);
                } catch (IllegalArgumentException e) {
                    // 聚合根的校验用 IllegalArgumentException 表达（纯 Java 层不认识业务错误码），
                    // 在应用层翻译成结构化业务错误，客户端才能显示「你还没抽到这名武将」
                    throw new BizException(ErrorCode.PARAM_INVALID, e.getMessage());
                }
                save(playerId, roster, version);
                Lineup lineup = roster.lineup(req.presetIndex(), rules);
                // 统帅值算一次就存进局部变量：原先两处各调一遍 teamBonus，那时只是浪费；
                // 现在它要读装备账本，两处之间若背包变了，日志里的"统帅值"就会与
                // 回给客户端的 troopCap 不是同一次算出来的 —— 那种对不上账最难查
                long commandValue = stats.teamBonus(equipLedgers.of(playerId), lineup, roster)
                        .commandValue();
                long troopCap = HeroCalculator.troopCap(commandValue, rules);
                LOG.info("设置编队 playerId={} preset={} 主将={} 副将=[{}, {}] 统帅值={} 带兵上限={}",
                        playerId, req.presetIndex(), req.main(), req.sub1(), req.sub2(),
                        commandValue, troopCap);
                return new SetLineupResp(toLineupView(equipLedgers.of(playerId), lineup, roster), troopCap,
                        timeService.serverNow());
            } catch (RuntimeException e) {
                idempotency.release(req.requestId());
                throw e;
            }
        });
    }

    /**
     * 校验随军出征的武将：数量不得超过编队上限、不得重复、必须已拥有。
     *
     * <p><b>为什么必须在出征那一刻拒掉</b>：这条校验原先不存在，非法 heroId 会一路活到战斗结算，
     * 而 {@code HeroRoster.hero} 对它抛的是 {@code IllegalStateException} —— 那发生在
     * 到期扫描里，{@code processDue} 会把这支行军留在队列中反复重试。玩家看到的是一支
     * 「永远卡在目标前、既不前进也不到家」的队伍，而日志里一切正常（B07 记过同一条纪律）。
     * 提示必须出现在玩家还能改主意的时刻（B09 验收 3 的精神）。
     *
     * <p><b>数量上限是平衡问题，不是格式问题</b>：每个随军武将提供一整套攻防加成，
     * 没有上限就等于客户端可以自己决定带几个人的 buff，而 {@code LINEUP_HERO_COUNT}
     * 这个配置项形同虚设。
     *
     * @param heroIds 随军武将 id。允许为空（一个都不带是合法出征），空位用 null/空串表示
     */
    public void requireOnMarch(String playerId, List<String> heroIds) {
        if (heroIds == null || heroIds.isEmpty()) {
            return;
        }
        int max = stats.rules().lineupSize();
        if (heroIds.size() > max) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "随军武将不得超过 " + max + " 个（主将与副将位合计），当前 " + heroIds.size() + " 个");
        }
        // 只读：这里不能用 loadOrCreate —— 那会给一个还没获得过武将的玩家凭空写一张空表
        HeroRoster roster = heroes.findByPlayerId(playerId).orElseGet(HeroRoster::new);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String heroId : heroIds) {
            if (heroId == null || heroId.isBlank()) {
                continue;   // 空位允许（B06 的 Lineup 同样用 null 表示空副将位）
            }
            if (!seen.add(heroId)) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "同一武将不能占两个随军位：" + heroId + "，加成不该按它出现几次来叠");
            }
            if (!roster.owns(heroId)) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "尚未拥有武将 " + heroId + "，无法带它出征");
            }
        }
    }

    // ---------- 内部 ----------

    /** 升星：扣该稀有度的碎片 → 升一星 → 落库。 */
    private HeroGrowResp growByFragment(String playerId, HeroIdReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        HeroCfg cfg = requireHeroCfg(req.heroId());
        long need = rarityEconomy(cfg.rarity()).starUpFragment();
        String fragmentItem = stats.fragmentItemId(cfg.rarity());
        ItemCfg fragmentCfg = requireItem(fragmentItem);

        return guarded(playerId, req.requestId(), (roster, version) -> {
            HeroInstance instance = requireHero(roster, req.heroId());
            int before = instance.star();
            // 先校验能不能升，再扣碎片：顺序反了的话「已满星」也会先扣一次碎片再报错
            if (before >= stats.rules().starMax()) {
                throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                        cfg.name() + " 已达星级上限 " + stats.rules().starMax() + " 星");
            }
            consumeItem(playerId, fragmentItem, need, fragmentCfg.name());
            try {
                instance.starUp(stats.rules());
            } catch (RuntimeException e) {
                bagPort.add(playerId, fragmentItem, need);
                throw e;
            }
            save(playerId, roster, version);
            LOG.info("武将升星 playerId={} hero={} ★{}→★{} 消耗={}×{}",
                    playerId, req.heroId(), before, instance.star(), fragmentItem, need);
            return new HeroGrowResp(toView(equipLedgers.of(playerId), instance),
                    List.of(new ItemCount(fragmentItem, need)), timeService.serverNow());
        });
    }

    private HeroGrowResp growByItem(String playerId, HeroItemReq req,
                                    ItemCfg.EffectKind expected, String action) {
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        ItemCfg itemCfg = requireItem(req.itemId());
        if (itemCfg.effectKind() != expected) {
            throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                    "道具 " + itemCfg.name() + " 的效果是 " + itemCfg.effectKind()
                            + "，不能用于" + ("awaken".equals(action) ? "觉醒" : "技能升级"));
        }
        boolean skillUp = "skillUp".equals(action);
        if (skillUp && req.skillSlot() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "技能升级必须指定 skillSlot（MAIN / SUB）");
        }
        HeroCfg heroCfg = requireHeroCfg(req.heroId());

        return guarded(playerId, req.requestId(), (roster, version) -> {
            HeroInstance instance = requireHero(roster, req.heroId());
            // 觉醒石分初阶/高阶：最后一阶只能用高阶石（item 表 effectValue 记的是阶数增量，
            // 而「哪一阶能用哪种石头」由 hero 表的 awakenMax 与道具稀有度共同决定）
            if (!skillUp) {
                int maxAwaken = (int) heroCfg.awakenMax();
                if (instance.awaken() >= maxAwaken) {
                    throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                            heroCfg.name() + " 已达觉醒上限 " + maxAwaken + " 阶");
                }
                boolean finalTier = instance.awaken() + 1 == maxAwaken;
                boolean highTierStone = itemCfg.rarity() == ItemCfg.Rarity.SSR;
                if (finalTier != highTierStone) {
                    throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                            finalTier ? "最后一阶觉醒必须使用高阶觉醒石（item_hero_awaken_2），当前是 "
                                    + itemCfg.name()
                                    : itemCfg.name() + " 只能用于最后一阶觉醒，当前是第 "
                                    + (instance.awaken() + 1) + " 阶");
                }
            } else {
                boolean main = req.skillSlot().name().equals("MAIN");
                int current = main ? instance.mainSkillLevel() : instance.subSkillLevel();
                if (current >= stats.rules().skillMaxLevel()) {
                    throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                            (main ? "主" : "副") + "技能已达上限 "
                                    + stats.rules().skillMaxLevel() + " 级");
                }
                // 技能书的 effectTarget 标明了它升的是主技能还是副技能，
                // 与请求里的 skillSlot 不一致就是客户端串了，必须拒绝而不是猜
                String target = itemCfg.effectTarget();
                if (target != null && !target.equals(req.skillSlot().name())) {
                    throw new BizException(ErrorCode.ITEM_CANNOT_USE,
                            itemCfg.name() + " 只能升 " + target + " 技能，请求的却是 "
                                    + req.skillSlot().name());
                }
            }

            consumeItem(playerId, req.itemId(), 1L, itemCfg.name());
            try {
                if (skillUp) {
                    instance.skillUp(req.skillSlot().name().equals("MAIN"), stats.rules());
                } else {
                    instance.awakenUp((int) heroCfg.awakenMax());
                }
            } catch (RuntimeException e) {
                bagPort.add(playerId, req.itemId(), 1L);
                throw e;
            }
            save(playerId, roster, version);
            LOG.info("武将{} playerId={} hero={} item={} 结果={}",
                    skillUp ? "技能升级" : "觉醒", playerId, req.heroId(), req.itemId(), instance);
            return new HeroGrowResp(toView(equipLedgers.of(playerId), instance),
                    List.of(new ItemCount(req.itemId(), 1L)), timeService.serverNow());
        });
    }

    /** 「幂等 → 锁 → 读档 → 执行 → 落库」的统一骨架，所有养成操作共用。 */
    private HeroGrowResp guarded(String playerId, String requestId, GrowAction action) {
        long now = timeService.serverNow();
        acquireIdempotency(requestId, playerId, now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                HeroRoster roster = loadOrCreate(playerId);
                long version = heroes.versionOf(playerId);
                return action.run(roster, version);
            });
        } catch (RuntimeException e) {
            idempotency.release(requestId);
            throw e;
        }
    }

    private interface GrowAction {
        HeroGrowResp run(HeroRoster roster, long version);
    }

    private void acquireIdempotency(String requestId, String playerId) {
        acquireIdempotency(requestId, playerId, timeService.serverNow());
    }

    private void acquireIdempotency(String requestId, String playerId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "requestId 不得为空");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private HeroRoster loadOrCreate(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "playerId 不得为空");
        }
        var existing = heroes.findByPlayerId(playerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        heroes.insertIfAbsent(playerId, new HeroRoster());
        return heroes.findByPlayerId(playerId)
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "武将存档创建后立即读不到"));
    }

    private void save(String playerId, HeroRoster roster, long version) {
        heroes.save(playerId, roster, version);
    }

    private void consumeItem(String playerId, String itemId, long count, String itemName) {
        // 原子扣减：不足则一个都不扣（B04 验收 10 的同一条纪律）
        if (bagPort.remove(playerId, itemId, count) == 0L) {
            throw new BizException(ErrorCode.ITEM_NOT_ENOUGH,
                    "需要 " + itemName + " " + count + " 个，当前持有 "
                            + bagPort.countOf(playerId, itemId) + " 个");
        }
    }

    private void refundAll(List<ItemCount> consumed, String playerId) {
        for (ItemCount item : consumed) {
            long back = bagPort.add(playerId, item.itemId(), item.count());
            if (back < item.count()) {
                LOG.error("【养成材料退还失败】playerId={} item={} 应退={} 实退={} traceId={}",
                        playerId, item.itemId(), item.count(), back, TraceContext.traceId());
            }
        }
    }

    private HeroInstance requireHero(HeroRoster roster, String heroId) {
        if (heroId == null || heroId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "heroId 不得为空");
        }
        if (!roster.owns(heroId)) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "尚未拥有武将 " + heroId);
        }
        return roster.hero(heroId);
    }

    private HeroCfg requireHeroCfg(String heroId) {
        if (heroId == null || heroId.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "heroId 不得为空");
        }
        try {
            return configs.get(HeroCfg.class, heroId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "武将配置不存在: " + heroId);
        }
    }

    private ItemCfg requireItem(String itemId) {
        try {
            return configs.get(ItemCfg.class, itemId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND, "道具配置不存在: " + itemId);
        }
    }

    /**
     * 把"穿哪一件"的请求值解析成 uid。
     *
     * <p>两种输入都接受，因为形状本身就能分辨（{@link Inventory#isInstanceUid}）：
     * uid 直接用；配置行 id 是老客户端的写法，取该行的第一件未穿。
     * 走后者时打 ERROR：<b>那不是玩家的错，是界面还没跟上"件"这个概念</b>，
     * 而这条日志就是"还剩多少请求在走老路"的量具 —— 没有它，这条兼容路径永远不会被删掉。
     */
    private String resolveWearableUid(String playerId, String requested) {
        if (Inventory.isInstanceUid(requested)) {
            return requested;
        }
        Inventory bag = inventories.findByPlayerId(playerId).orElse(null);
        String uid = bag == null ? null : bag.firstUnwornEquip(requested);
        if (uid == null) {
            throw new BizException(ErrorCode.ITEM_NOT_FOUND,
                    "背包里没有可用的 " + requested + "（请传装备实例 uid，或先拿到一件没穿的）");
        }
        LOG.error("【穿戴请求传的是配置行 id 而不是 uid】playerId={} equipId={} 本次按未穿的第一件处理"
                + " uid={} 客户端改传 uid 之后这条兼容解析要一并删掉", playerId, requested, uid);
        return uid;
    }

    /**
     * 技能中文名，随 {@code HeroView} 下发（#255 建筑名、#268 资源名之后的第三处配置 id 外泄）。
     *
     * <p>查不到就退回 id 并打 ERROR：少一行名字不该让整张武将页画不出来，
     * 但**也不能静默把 id 印给玩家** —— 那条日志就是"还有几行没配名字"的量具
     * （与 {@link #resolveWearableUid} 那条兼容路径同一做法）。
     */
    private String skillName(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "";
        }
        try {
            return configs.get(SkillCfg.class, skillId).name();
        } catch (ConfigException e) {
            LOG.error("【skill 表查不到这一行】skillId={} 界面会退回显示这个 id", skillId);
            return skillId;
        }
    }

    private EquipCfg requireEquip(String equipId) {
        try {
            return configs.get(EquipCfg.class, equipId);
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND, "装备配置不存在: " + equipId);
        }
    }

    private HeroRarityCfg rarityEconomy(HeroCfg.Rarity rarity) {
        try {
            return configs.get(HeroRarityCfg.class, rarity.name());
        } catch (ConfigException e) {
            throw new BizException(ErrorCode.CONFIG_NOT_FOUND,
                    "hero_rarity 表缺少 " + rarity + " 档，碎片经济无法结算");
        }
    }

    /**
     * 某个玩家各稀有度的碎片余额，直接读背包 —— 碎片是道具，背包是唯一真相。
     *
     * <p>某一档没有对应碎片道具时跳过（例如 N 档可能不投放碎片），那不是配置错误。
     *
     * <p><b>名字随行下发</b>（#255 建筑名、#268 资源名、#278 技能名之后的同族第四处）：
     * 这一行**包含余数为 0 的档**，而背包只列余数大于 0 的行 ——
     * 让客户端去 join 背包的结果是"越没有越看不见名字"，正好退回印行 id。
     *
     * <p><b>门槛与候选也随行下发</b>（V03-d 第六条线：碎片合成武将）：玩家要说的是"还差 38 片就能合成典韦"，
     * 而这句话要的三个数（余额、门槛、可合成名单）客户端一个都不该自己算 ——
     * 门槛在 hero_rarity 表里，名单在 hero 表里，未拥有与否在存档里。
     * 客户端抄表的后果是表一改就见人说"够了"，然后被服务端拒绝。
     *
     * @param roster 该玩家当前的武将名册，用于排除已拥有的（`compose` 对已拥有直接拒绝，
     *               留着只会让玩家点一行注定失败的武将）
     */
    public List<FragmentView> fragmentBalancesOf(String playerId, HeroRoster roster) {
        List<FragmentView> out = new ArrayList<>();
        List<HeroCfg> allHeroes = configs.all(HeroCfg.class);
        for (HeroCfg.Rarity rarity : HeroCfg.Rarity.values()) {
            String itemId;
            try {
                itemId = stats.fragmentItemId(rarity);
            } catch (ConfigException e) {
                continue;
            }
            List<ComposeCandidate> candidates = new ArrayList<>();
            for (HeroCfg hero : allHeroes) {
                if (hero.rarity() == rarity && !roster.owns(hero.id())) {
                    candidates.add(new ComposeCandidate(hero.id(), hero.name()));
                }
            }
            out.add(new FragmentView(itemId, itemName(itemId), bagPort.countOf(playerId, itemId),
                    rarityEconomy(rarity).composeFragment(), candidates));
        }
        return out;
    }

    /** 道具中文名；查不到就退回 id 并打 ERROR（同 {@link #skillName}：不空页，也不静默印 id）。 */
    private String itemName(String itemId) {
        try {
            return configs.get(ItemCfg.class, itemId).name();
        } catch (ConfigException e) {
            LOG.error("【item 表查不到这一行】itemId={} 界面会退回显示这个 id", itemId);
            return itemId;
        }
    }

    private HeroView toView(EquipLedger equips, HeroInstance instance) {
        HeroCfg cfg = stats.heroCfg(instance.heroId());
        HeroRules rules = stats.rules();
        HeroAttrs base = HeroAttrs.of(cfg.might(), cfg.command(), cfg.wisdom());
        HeroAttrs equipFlat = stats.equipFlat(equips, instance);
        HeroAttrs finalAttrs = HeroCalculator.finalAttrs(base, cfg.growthRate(),
                instance.level(), instance.star(), instance.awaken(), equipFlat, rules);
        // 只列**穿着的**：空槽不出现在数组里，每项自己带着 slot（形状的理由写在 HeroView.equips 的契约描述里）。
        // 老存档那一路（槽位值直接是行 id）由 EquipLedger#resolve 按 +0 解析，照样会出现在这里 ——
        // 那是"玩家穿着东西"的既有事实，不能因为形状换了就当没穿
        List<WornEquip> worn = new ArrayList<>(EquipSlot.COUNT);
        for (EquipSlot slot : EquipSlot.values()) {
            String slotValue = instance.equipOf(slot);
            if (slotValue == null || slotValue.isBlank()) {
                continue;
            }
            EquipLedger.Resolved resolved = equips.resolve(slotValue);
            if (resolved == null) {
                // 悬空引用（EquipLedger 已经打过 ERROR）：宁可少画一项，
                // 也不画一件不存在的东西 —— 更不能把那个 uid 印到玩家眼前
                continue;
            }
            worn.add(new WornEquip(
                    // 域内那份 EquipSlot 与契约生成的那份是两个类型（常量与顺序同源），DTO 要后者。
                    // 用 valueOf 而不是 ordinal：哪天两边对不上，宁可当场炸也不要静默错位一格
                    com.ironoath.web.dto.generated.EquipSlot.valueOf(slot.name()),
                    slotValue, resolved.equipId(),
                    equipName(resolved.equipId()), resolved.forgeLevel()));
        }
        return new HeroView(cfg.id(), cfg.name(), HeroRarity.valueOf(cfg.rarity().name()),
                instance.level(), instance.exp(),
                rules.expToNext(instance.level(), (int) cfg.maxLevel()),
                (int) cfg.maxLevel(),
                instance.star(), rules.starMax(), instance.awaken(), (int) cfg.awakenMax(),
                cfg.mainSkill(), skillName(cfg.mainSkill()), instance.mainSkillLevel(),
                cfg.subSkill(), skillName(cfg.subSkill()), instance.subSkillLevel(), rules.skillMaxLevel(),
                worn, toTriple(base), toTriple(finalAttrs),
                stats.power(equips, instance), cfg.bondWith());
    }

    /** 装备中文名；查不到退回行 id 并打 ERROR（同 {@link #skillName}：不空页，也不静默印 id）。 */
    private String equipName(String equipId) {
        try {
            return configs.get(EquipCfg.class, equipId).name();
        } catch (ConfigException e) {
            LOG.error("【equip 表查不到这一行】equipId={} 界面会退回显示这个行 id", equipId);
            return equipId;
        }
    }

    private LineupView toLineupView(EquipLedger equips, Lineup lineup, HeroRoster roster) {
        HeroCalculator.TeamBonus bonus = stats.teamBonus(equips, lineup, roster);
        List<String> bonds = new ArrayList<>();
        for (String heroId : lineup.members()) {
            String partner = stats.heroCfg(heroId).bondWith();
            if (partner != null && lineup.members().contains(partner)
                    && heroId.compareTo(partner) < 0) {
                bonds.add(heroId + "+" + partner);
            }
        }
        return new LineupView(lineup.presetIndex(), lineup.main(),
                lineup.subs().size() > 0 ? lineup.subs().get(0) : null,
                lineup.subs().size() > 1 ? lineup.subs().get(1) : null,
                toBonus(bonus), bonds);
    }

    private HeroBonus toBonus(HeroCalculator.TeamBonus bonus) {
        List<BonusBreak> breakdown = new ArrayList<>(bonus.breakdown().size());
        for (HeroCalculator.Break b : bonus.breakdown()) {
            breakdown.add(new BonusBreak(b.source(), b.valueFixed(),
                    BonusZone.valueOf(b.zone().name())));
        }
        return new HeroBonus(bonus.atkFixed(), bonus.defFixed(), bonus.skillFixed(),
                bonus.commandValue(), bonus.capped(), breakdown);
    }

    private static AttrTriple toTriple(HeroAttrs attrs) {
        return new AttrTriple(attrs.might(), attrs.command(), attrs.wisdom());
    }

}
