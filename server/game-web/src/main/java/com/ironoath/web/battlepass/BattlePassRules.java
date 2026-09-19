package com.ironoath.web.battlepass;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.BattlePassCfg;
import com.ironoath.config.cfg.BattlePassSeasonCfg;
import com.ironoath.web.season.SeasonRulesAssembler;

import java.util.Comparator;
import java.util.List;

/**
 * 职责：把 {@code battle_pass} / {@code battle_pass_season} 两张表装配成可用的规则 —— 唯一的读表入口。
 * 依赖：{@link ConfigRegistry}、{@link SeasonRulesAssembler}（赛季 id 与时间轴）。
 *
 * <p><b>赛季 id 只从 {@code SeasonRulesAssembler} 取，绝不在本类里另推一遍</b>：
 * 赛季账本（{@code SeasonLedgerStore}）、商店的 {@code SEASON} 限购、战令进度键三处
 * 必须是**同一个字符串**，否则"换赛季归零"这件事会在其中一个域里悄悄不成立。
 */
public class BattlePassRules {

    private final ConfigRegistry configs;
    private final SeasonRulesAssembler seasons;

    public BattlePassRules(ConfigRegistry configs, SeasonRulesAssembler seasons) {
        this.configs = configs;
        this.seasons = seasons;
    }

    /** 当前赛季 id（与赛季账本、商店 SEASON 限购同源）。 */
    public String seasonId() {
        return seasons.timelineRules().seasonId();
    }

    /** 本赛季总时长（天）—— 下发给客户端算"还剩几天"用。 */
    public long seasonTotalDays() {
        return seasons.timelineRules().totalDays();
    }

    /** 20 档，按 tier 升序。顺序即界面顺序，表里怎么排不影响这里。 */
    public List<BattlePassCfg> tiers() {
        return configs.all(BattlePassCfg.class).stream()
                .sorted(Comparator.comparingLong(BattlePassCfg::tier))
                .toList();
    }

    /** 按档位号取一行。不存在的档位号是**参数错**而不是"没达成" —— 前端传错档位时要说清楚。 */
    public BattlePassCfg requireTier(long tier) {
        for (BattlePassCfg row : tiers()) {
            if (row.tier() == tier) {
                return row;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "没有第 " + tier + " 档战令奖励");
    }

    /**
     * 本赛季的限定外观（{@code battle_pass_season} 表按当前赛季 id 查）。
     *
     * <p>没配这一行时返回 null，**不是**抛：赛季与外观的对应是策划数据，
     * 缺一行应当让"买了战令但本赛季没有限定框"这件事成立（发货侧会跳过并在日志里留痕），
     * 而不是让整个购买链路失败 —— 玩家付了钱，没有理由因为一件外观没配而发不了货。
     */
    public String seasonFrameId() {
        String seasonId = seasonId();
        for (BattlePassSeasonCfg row : configs.all(BattlePassSeasonCfg.class)) {
            if (seasonId.equals(row.id())) {
                return row.frameId();
            }
        }
        return null;
    }

    /** 这一档的达成线（累计分）。 */
    public long requiredPointsOf(long tier) {
        return requireTier(tier).requiredPoints();
    }

    /** 打满全部档位需要的总分 —— 与买断定价的口径同源（一档 150 分 × 20 档 = 3000）。 */
    public long totalPoints() {
        List<BattlePassCfg> rows = tiers();
        return rows.isEmpty() ? 0L : rows.get(rows.size() - 1).requiredPoints();
    }

    /** 表里有没有这个 rewardId（资源查 resource 表、道具查 item 表）—— 查不到就是配置故障。 */
    public boolean rewardExists(boolean resource, String rewardId) {
        try {
            if (resource) {
                configs.getResource(rewardId);
            } else {
                configs.get(com.ironoath.config.cfg.ItemCfg.class, rewardId);
            }
            return true;
        } catch (ConfigException e) {
            return false;
        }
    }
}
