package com.ironoath.web.service;

import com.ironoath.common.num.FixedPoint;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BuildingCfg;
import com.ironoath.config.cfg.UnitCfg;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.city.BuildingInstance;
import com.ironoath.core.city.CityState;
import com.ironoath.core.formula.Formula;
import com.ironoath.core.hero.HeroInstance;
import com.ironoath.core.hero.HeroRoster;
import com.ironoath.web.hero.EquipLedger;
import com.ironoath.web.hero.EquipLedgers;
import com.ironoath.core.hero.Lineup;
import com.ironoath.core.power.PowerBandGuard;
import com.ironoath.core.power.PowerCalculator;
import com.ironoath.core.power.Protection;
import com.ironoath.core.power.TargetSearch;
import com.ironoath.core.power.Tyranny;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 职责：把配置表解析成 B08 的三套规则，并从城建/军队/武将状态算出玩家战力。
 * 依赖：game-config（读表）、game-core（纯计算）。
 *
 * <p><b>圈层规则只能在这一处装配</b>（B08 禁止项：不要新增绕过统一中间件校验的代码路径）。
 * PVP_POWER_MIN_RATIO / PVP_POWER_MAX_RATIO 这两个参数只允许被本类读取，
 * CI 的 check-no-handout.sh 会扫主源码里对它们的直接读取并提示复核 ——
 * 因为任何一处「自己算区间」都意味着绕过了 √N 破圈与峰值记忆。
 *
 * <p><b>展示战力与匹配战力分开算</b>，理由见 {@link PowerCalculator} 的类注释：
 * 用展示战力做圈层校验会立刻被「卸兵压分」套利。
 */
@Service
public class PowerService {

    private final ConfigRegistry configs;
    private final HeroStatsService heroStats;
    private final EquipLedgers equipLedgers;

    public PowerService(ConfigRegistry configs, HeroStatsService heroStats,
                        EquipLedgers equipLedgers) {
        this.configs = configs;
        this.heroStats = heroStats;
        this.equipLedgers = equipLedgers;
    }

    /** 圈层规则（区间 [0.5x, 2.0x]，集结按 √N 放宽）。 */
    public PowerBandGuard.Rules bandRules() {
        return new PowerBandGuard.Rules(
                configs.fixedParam("PVP_POWER_MIN_RATIO"),
                configs.fixedParam("PVP_POWER_MAX_RATIO"),
                // B08 禁止项：集结门槛不得用 N 而要用 √N，所以这里恒为 true。
                // 不读配置是刻意的 —— 把它做成可配置就等于留了一个「一键改成线性放宽」的开关，
                // 而那个开关一旦被打开，高战集结会无限膨胀，且没有任何测试会变红
                true);
    }

    /** 峰值记忆规则。 */
    public PowerCalculator.Rules powerRules() {
        return new PowerCalculator.Rules(
                configs.fixedParam("PEAK_POWER_MEMORY_RATIO"),
                configs.fixedParam("PEAK_POWER_DAILY_DECAY"));
    }

    /** 暴虐值规则。 */
    public Tyranny.Rules tyrannyRules() {
        return new Tyranny.Rules(
                configs.fixedParam("TYRANNY_THRESHOLD"),
                configs.longParam("TYRANNY_PER_UNIT"),
                configs.longParam("TYRANNY_CRUSH_MULTIPLIER"),
                configs.fixedParam("BRUTALITY_DAILY_DECAY"),
                configs.longParam("BRUTALITY_TYRANT_THRESHOLD"),
                configs.longParam("BRUTALITY_BRUTE_THRESHOLD"),
                configs.longParam("BRUTALITY_ENEMY_THRESHOLD"));
    }

    /**
     * 三条基础保护的规则（B08 §7）。
     *
     * <p><b>只有三条，不许加第四条</b>。想加就必须同时改 match_rule.json 的 PROTECTION 行
     * 与那条断言「恰好 3 行」的单测 —— 三处一起改才动得了，这是 C00 公理二的可执行化。
     */
    public Protection.Rules protectionRules() {
        return new Protection.Rules(
                (int) configs.longParam("NEWCOMER_PROTECT_CITY_LEVEL"),
                configs.longParam("VICTIM_SHIELD_WINDOW_SECONDS") * 1000L,
                (int) configs.longParam("VICTIM_SHIELD_TRIGGER_COUNT_TIER1"),
                configs.longParam("VICTIM_SHIELD_HOURS_TIER1") * 3600_000L,
                (int) configs.longParam("VICTIM_SHIELD_TRIGGER_COUNT_TIER2"),
                configs.longParam("VICTIM_SHIELD_HOURS_TIER2") * 3600_000L);
    }

    /** 目标搜索的规则（B08 §8）。 */
    public TargetSearch.Rules searchRules() {
        return new TargetSearch.Rules(
                (int) configs.longParam("SEARCH_MAX_RADIUS"),
                configs.longParam("SEARCH_ACTIVE_WINDOW_HOURS") * 3600_000L,
                (int) configs.longParam("SEARCH_DEFAULT_COUNT"),
                (int) configs.longParam("SEARCH_MAX_COUNT"),
                configs.fixedParam("SEARCH_WEIGHT_POWER"),
                configs.fixedParam("SEARCH_WEIGHT_DISTANCE"),
                configs.fixedParam("SEARCH_WEIGHT_RESOURCE"),
                configs.fixedParam("SEARCH_WEIGHT_RANDOM"),
                configs.fixedParam("SEARCH_NEAR_RATIO"),
                configs.fixedParam("SEARCH_MID_RATIO"),
                configs.fixedParam("SEARCH_RICH_RATIO"),
                configs.fixedParam("SEARCH_POOR_RATIO"),
                configs.fixedParam("SEARCH_PEER_RATIO_MIN"),
                configs.fixedParam("SEARCH_PEER_RATIO_MAX"));
    }

    /**
     * 从锚点时刻到 now 过了多少个「衰减日」（向下取整，负数按 0）。
     *
     * <p>峰值与暴虐值的每日衰减都用这个口径：惰性补算，不跑定时器。
     * 一天的长度取 global.DAY_SECONDS 而不是自然日 —— 自然日会让衰减在凌晨集中发生，
     * 于是「几点上线」影响衰减多少，跨时区部署时同一份存档还会算出不同天数。
     *
     * <p><b>调用方必须把锚点推进「整天数」而不是推进到 now</b>，否则一天上线两次的玩家
     * 永远算不满一天，衰减形同虚设。见 {@link PowerCalculator.Result#peakFromCurrent()}。
     */
    public int daysSince(long anchorMillis, long now) {
        long dayMillis = configs.longParam("DAY_SECONDS") * 1000L;
        if (anchorMillis <= 0L || now <= anchorMillis) {
            return 0;
        }
        long days = (now - anchorMillis) / dayMillis;
        return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
    }

    /** 锚点推进整天数后的新时刻（保留不足一天的余数）。 */
    public long advanceWholeDays(long anchorMillis, int days) {
        if (anchorMillis <= 0L) {
            throw new IllegalArgumentException("锚点必须为正的服务端时间戳，实际=" + anchorMillis);
        }
        if (days < 0) {
            throw new IllegalArgumentException("天数不得为负：" + days);
        }
        return anchorMillis + days * configs.longParam("DAY_SECONDS") * 1000L;
    }

    /**
     * 算一名玩家的战力。
     *
     * @param storedPeak        存档里的历史峰值
     * @param daysSincePeakTouch 距上次刷新峰值的天数（惰性衰减，不跑定时器）
     */
    public PowerCalculator.Result powerOf(String playerId, CityState city, ArmyState army,
                                          HeroRoster roster, long storedPeak, int daysSincePeakTouch) {
        // 装备是按件记的实例，而实例账本在背包里：武将名档说不出"这些装备属于谁"，只能由调用方给 id。
        // 不"从 roster 反推"：反推成 null 的症状是这个人装备全部不计，而它看起来只是战力低了一点
        EquipLedger equips = equipLedgers.of(playerId);
        long building = buildingPower(city);
        long troops = troopsPower(army);
        long heroes = heroesPower(equips, roster);
        // 科技与装备：科技属 B12（PlayerSave 尚无 tech 字段），装备已并入武将战力
        // （HeroCalculator.power 的入参含装备固定值），所以这里不重复计一次
        PowerCalculator.PowerBreakdown breakdown =
                new PowerCalculator.PowerBreakdown(building, troops, heroes, 0L, 0L);
        long currentMatch = matchPowerOf(equips, army, roster);
        return PowerCalculator.compute(
                new PowerCalculator.Snapshot(breakdown, currentMatch, storedPeak, daysSincePeakTouch),
                powerRules());
    }

    /** 建筑战力：Σ 各建筑的 POWER_CONTRIB 贡献（与 B03 升级响应的 powerDelta 同一口径）。 */
    public long buildingPower(CityState city) {
        long exponent = configs.curve("POWER_CONTRIB").exponentFixed();
        long total = 0L;
        for (BuildingInstance b : city.buildings()) {
            BuildingCfg cfg = configs.get(BuildingCfg.class, b.configId());
            if (cfg.powerBase() <= 0L || b.level() <= 0) {
                continue;
            }
            total += FixedPoint.round(
                    Formula.powerContribution(FixedPoint.of(cfg.powerBase()), b.level(), exponent));
        }
        return total;
    }

    /** 部队战力：Σ(兵数 × 该兵种的攻+防+血)。用 unit 表原值，不套阶级曲线（表里已是逐阶级的值）。 */
    public long troopsPower(ArmyState army) {
        long total = 0L;
        for (Map.Entry<String, Long> entry : army.troops().entrySet()) {
            UnitCfg unit = configs.get(UnitCfg.class, entry.getKey());
            total += entry.getValue() * (unit.attack() + unit.defense() + unit.hp());
        }
        return total;
    }

    /** 武将战力：全部已拥有武将之和（含未上阵的 —— 展示战力要反映「我练了多少」）。 */
    public long heroesPower(EquipLedger equips, HeroRoster roster) {
        long total = 0L;
        for (HeroInstance hero : roster.heroes()) {
            total += heroStats.power(equips, hero);
        }
        return total;
    }

    /** 手里只有 id、还没有快照时的入口（战斗那条链就是这个形状）。 */
    public long matchPowerOf(String playerId, ArmyState army, HeroRoster roster) {
        return matchPowerOf(equipLedgers.of(playerId), army, roster);
    }

    /**
     * 当前匹配战力 = 当前部队 + 上阵主将。
     *
     * <p><b>只算第 0 套预设的上阵武将</b>：能跟着出征的只有当前生效的那一套，
     * 把三套预设都算进去等于让玩家靠「编三套队」虚增匹配战力，
     * 而匹配战力是圈层校验的唯一依据 —— 虚增它就能打超出圈层的对手。
     */
    public long matchPowerOf(EquipLedger equips, ArmyState army, HeroRoster roster) {
        long match = troopsPower(army);
        Lineup lineup = roster.lineup(0, heroStats.rules());
        for (String heroId : lineup.members()) {
            match += heroStats.power(equips, roster.hero(heroId));
        }
        return match;
    }
}
