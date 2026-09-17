package com.ironoath.web.service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.EquipCfg;
import com.ironoath.config.cfg.EquipSetCfg;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.core.hero.HeroAttrs;
import com.ironoath.core.hero.HeroCalculator;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRules;
import com.ironoath.core.hero.Lineup;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.web.hero.EquipLedger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 职责：把武将配置解析成 {@link HeroRules} 与具体属性 —— game-core 的武将逻辑与配置表之间唯一的桥。
 * 依赖：game-config（读表）、game-core（纯计算）。
 *
 * <p><b>为什么要这一层</b>：game-core 不许读配置（铁律 2，否则武将逻辑就无法脱离容器跑单测），
 * 而 B05 的训练系统（带兵上限）、B07/B09 的战斗组装、B08 的战力圈层都要用到武将属性。
 * 如果每个调用方各自去解析 hero/equip/equip_set 三张表再调 HeroCalculator，
 * 「同一套装备算出两个不同的加成」这类分叉迟早会出现 ——
 * 这正是 B04 里 ResourceRateService 存在的同一个理由。
 *
 * <p><b>套装的 2 件与 4 件不叠加</b>：凑齐 4 件时只生效 4 件套效果（+15%），
 * 而不是 2 件套 + 4 件套（+21%）。因为 equip_set 表里 4 件的数值就是按
 * 「明显高于两个 2 件套（6%+6%=12%）但不排他」来定的，叠加会让 15% 这个数失去意义。
 */
@Service
public class HeroStatsService {

    private final ConfigRegistry configs;

    public HeroStatsService(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 武将养成规则，逐项取自 global 与 curve 表（铁律 1：不硬编码）。 */
    public HeroRules rules() {
        return new HeroRules(
                configs.fixedParam("HERO_LEVEL_STEP"),
                configs.fixedParam("HERO_STAR_STEP"),
                configs.fixedParam("HERO_AWAKEN_STEP"),
                (int) configs.longParam("HERO_STAR_MAX"),
                (int) configs.longParam("HERO_SKILL_MAX_LEVEL"),
                configs.longParam("HERO_ATTR_PER_PERCENT"),
                configs.fixedParam("HERO_SUB_BONUS_RATIO"),
                configs.fixedParam("HERO_ZONE_CAP"),
                configs.fixedParam("HERO_BOND_BONUS"),
                configs.longParam("TROOP_PER_COMMAND"),
                (int) configs.longParam("LINEUP_HERO_COUNT"),
                (int) configs.longParam("LINEUP_PRESET_COUNT"),
                configs.curve("HERO_LEVEL_EXP").baseFixed(),
                configs.curve("HERO_LEVEL_EXP").ratioFixed(),
                configs.curve("HERO_GROWTH").exponentFixed());
    }

    public HeroCfg heroCfg(String heroId) {
        return configs.get(HeroCfg.class, heroId);
    }

    /** heroId → bondWith（null 表示没有缘分）。供 {@link HeroRoster#activeBonds} 使用。 */
    public Map<String, String> bondLookup() {
        Map<String, String> out = new LinkedHashMap<>();
        for (HeroCfg hero : configs.all(HeroCfg.class)) {
            out.put(hero.id(), hero.bondWith());
        }
        return out;
    }

    /** 某稀有度的碎片道具 id（item 表里 item_mat_hero_frag_*）。 */
    public String fragmentItemId(HeroCfg.Rarity rarity) {
        String suffix = rarity.name().toLowerCase(java.util.Locale.ROOT);
        String itemId = "item_mat_hero_frag_" + suffix;
        if (!configs.rawTable("item").has(itemId)) {
            throw new com.ironoath.config.ConfigException(
                    "item 表里缺少 " + rarity + " 稀有度的碎片道具 " + itemId
                            + "。碎片合成与重复武将转碎片都依赖它，缺了这一档就没法结算");
        }
        return itemId;
    }

    /**
     * 一名武将的养成后三维属性（含装备固定值）。
     *
     * @param equips 这个玩家的装备快照，见 {@link EquipLedger}（"读一次、判一次"的那个一次）
     */
    public HeroAttrs finalAttrs(EquipLedger equips, HeroInstance instance) {
        HeroCfg cfg = heroCfg(instance.heroId());
        HeroAttrs base = HeroAttrs.of(cfg.might(), cfg.command(), cfg.wisdom());
        return HeroCalculator.finalAttrs(base, cfg.growthRate(),
                instance.level(), instance.star(), instance.awaken(),
                equipFlat(equips, instance), rules());
    }

    /** 一名武将的战力。 */
    public long power(EquipLedger equips, HeroInstance instance) {
        HeroCfg cfg = heroCfg(instance.heroId());
        return HeroCalculator.power(HeroAttrs.of(cfg.might(), cfg.command(), cfg.wisdom()),
                cfg.growthRate(), instance.level(), instance.star(), instance.awaken(),
                equipFlat(equips, instance), rules());
    }

    /**
     * 四件装备的固定值之和 —— <b>含每件的强化等级</b>（B20 §五②：每级 +5% 本行三维）。
     *
     * <p>空槽与解析不到的小结（悬空 uid）按 0 计。这里<b>不查配置表</b>：从 uid 到属性这条链
     * 只有一个家（{@link EquipLedger}），否则「面板显示的等级」与「算进属性的等级」会分别长在两处 ——
     * 那种分叉的症状是玩家说"我明明 +5 了"，而日志里每一处都是对的。
     */
    public HeroAttrs equipFlat(EquipLedger equips, HeroInstance instance) {
        long mightFixed = 0L;
        long commandFixed = 0L;
        long wisdomFixed = 0L;
        for (String slotValue : instance.equips().values()) {
            EquipLedger.Resolved resolved = equips.resolve(slotValue);
            if (resolved == null) {
                continue;
            }
            mightFixed += resolved.mightFixed();
            commandFixed += resolved.commandFixed();
            wisdomFixed += resolved.wisdomFixed();
        }
        // 四件的定点值先全加起来，最后只落地一次、向下取整：
        // 每件各舍一次的话，"取整的位置"就决定了玩家亏多少 —— 而那笔钱是他付过的
        return HeroAttrs.of(FixedPoint.truncate(mightFixed),
                FixedPoint.truncate(commandFixed), FixedPoint.truncate(wisdomFixed));
    }

    /**
     * 一套编队的三个套装加成（攻击/防御/技能方向），按件数判定 2 件套或 4 件套。
     *
     * <p>件数按<b>整支队伍</b>统计而不是按单个武将：套装是队伍级的效果，
     * 否则「四个人各穿一件」永远凑不齐，而队伍只有三个武将位、每人四个槽，
     * 按人算的话 4 件套要求一个人穿满同一套 —— 那会让混搭成为唯一选择。
     *
     * <p><b>件数按 {@code equipId} 而不是按 {@code uid} 统计</b>：两件同样的破军甲是 2 件破军，
     * 而它们是两个实例。套装看的是"穿的是哪一行"，强化看的才是"是哪一件"。
     */
    public long[] equipSetBonuses(EquipLedger equips, Lineup lineup, HeroRoster roster) {
        Map<String, Integer> pieces = new LinkedHashMap<>();
        for (String heroId : lineup.members()) {
            HeroInstance instance = roster.hero(heroId);
            for (String slotValue : instance.equips().values()) {
                EquipLedger.Resolved resolved = equips.resolve(slotValue);
                if (resolved == null) {
                    continue;
                }
                EquipCfg equip = configs.get(EquipCfg.class, resolved.equipId());
                if (equip.setId() != null) {
                    pieces.merge(equip.setId(), 1, Integer::sum);
                }
            }
        }
        long atk = 0L;
        long def = 0L;
        long skill = 0L;
        for (Map.Entry<String, Integer> entry : pieces.entrySet()) {
            EquipSetCfg set = configs.get(EquipSetCfg.class, entry.getKey());
            int count = entry.getValue();
            // 4 件生效 4 件套、2~3 件生效 2 件套，两者不叠加（见类注释）
            if (count >= 4) {
                atk += forAttr(set.pieces4Attr(), set.pieces4Ratio(), "atk");
                def += forAttr(set.pieces4Attr(), set.pieces4Ratio(), "def");
                skill += forAttr(set.pieces4Attr(), set.pieces4Ratio(), "skill");
            } else if (count >= 2) {
                atk += forAttr(set.pieces2Attr(), set.pieces2Ratio(), "atk");
                def += forAttr(set.pieces2Attr(), set.pieces2Ratio(), "def");
                skill += forAttr(set.pieces2Attr(), set.pieces2Ratio(), "skill");
            }
        }
        return new long[]{atk, def, skill};
    }

    /**
     * 套装加成只落在它声明的那一维上，其余两维为 0。
     *
     * <p>参数是枚举而不是字符串：生成的 {@code EquipSetCfg} 把 pieces2Attr 与 pieces4Attr
     * 做成了两个独立枚举（Pieces2Attr / Pieces4Attr），没有共同类型，
     * 所以这里按 {@code name()} 比较 —— 传枚举仍然有类型安全，只是不做跨枚举的类型统一。
     */
    private static long forAttr(Enum<?> attr, long ratioFixed, String dimension) {
        boolean hit = switch (dimension) {
            case "atk" -> "MIGHT".equals(attr.name());
            case "def" -> "COMMAND".equals(attr.name());
            default -> "WISDOM".equals(attr.name());
        };
        // ratioFixed 已经是定点值（DECIMAL 列生成时即定点 long），直接用，不能再 parse 一次
        return hit ? ratioFixed : 0L;
    }

    /** 一套编队的完整加成与统帅值。 */
    public HeroCalculator.TeamBonus teamBonus(EquipLedger equips, Lineup lineup, HeroRoster roster) {
        HeroAttrs main = null;
        List<HeroAttrs> subs = new ArrayList<>();
        if (lineup.isFormed()) {
            // 不再自己写 lineup.main() != null —— "算不算成形"这条规则住在 Lineup.isFormed() 里，
            // 定义哪天收紧（例如要求副将也到位），这里跟着走而不是按旧口径静默算加成
            main = finalAttrs(equips, roster.hero(lineup.main()));
        }
        for (String subId : lineup.subs()) {
            subs.add(subId == null ? null : finalAttrs(equips, roster.hero(subId)));
        }
        long[] sets = equipSetBonuses(equips, lineup, roster);
        int bonds = roster.activeBonds(lineup, bondLookup());
        return HeroCalculator.teamBonus(main, subs, sets[0], sets[1], sets[2], bonds, rules());
    }

    /**
     * 带兵上限（B05 §二、B06 验收 8）。
     *
     * <p><b>这里没有"等科技落地"那一说</b>：{@code tech.json} 的 {@code LOAD_CAPACITY} 放大的是
     * <b>行军的负载上限</b>（一队能带多少回家），与"一个武将统多少兵"是两个数；
     * 而表里也<b>没有</b>"带兵上限"这个效果属性。曾经的注释写着"届时把 LOAD_CAPACITY 接进来、
     * 签名不必变"——那句话会把人引去把运力加成乘进统兵上限，那是一项没人设计过的双重收益。
     * 真要给带兵上限加科技，需要先加一行表 + 一个属性（设计裁决），不是在这里接。
     *
     * @param presetIndex 用哪套预设；不指定时用第 0 套
     */
    public long troopCap(EquipLedger equips, HeroRoster roster, int presetIndex) {
        Lineup lineup = roster.lineup(presetIndex, rules());
        return HeroCalculator.troopCap(teamBonus(equips, lineup, roster).commandValue(), rules());
    }
}
