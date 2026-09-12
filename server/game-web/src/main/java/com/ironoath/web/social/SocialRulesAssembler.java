package com.ironoath.web.social;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.AllianceConfigCfg;
import com.ironoath.config.cfg.RolePermissionCfg;
import com.ironoath.config.cfg.SquadConfigCfg;
import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.ChatRateLimiter;
import com.ironoath.core.social.HelpLedger;
import com.ironoath.core.social.PermissionMatrix;
import com.ironoath.core.social.Rally;
import com.ironoath.core.social.Squad;

/**
 * 职责：把 squad_config / alliance_config / role_permission / global 装配成 game-core 的规则对象。
 * 依赖：game-config、game-core。
 *
 * <p><b>这一层是「配置驱动」与「内核纯净」之间的唯一桥</b>，与 {@code BattleRulesAssembler}
 * 是同一种东西：game-core 按 B00 分层规则读不到配置表，所以所有数值都必须由外层解析好再传进去。
 *
 * <p><b>本类最容易出的错是单位换算，所以三条规矩写在最前面</b>（BattleParamsResolver 踩过一次）：
 * <ol>
 *   <li>{@code helpSpeedBonus} / {@code techCapBonus} 在表里声明为 DECIMAL，
 *       已由 FixedPointDeserializer 转成定点 long（0.01 → 100），<b>不要再转一次</b>。
 *       多转一次不报错，只会让「每次帮助减 1%」变成「减 100%」—— 一次帮助就把升级清零</li>
 *   <li>{@code memberCap} / {@code rallyCapacity} / {@code donationDailyCap} 声明为 LONG_POS，
 *       是<b>普通整数计数</b>，不参与定点运算，原样传入</li>
 *   <li>global 里所有 {@code *_SECONDS} 参数都是秒，而 core 的规则对象要毫秒，
 *       <b>必须 ×1000</b>。漏乘的表现是「解散保护期只有 86400 毫秒 = 86 秒」，
 *       验收 7 会过（因为保护期确实存在）而线上完全失效</li>
 * </ol>
 *
 * <p><b>每次调用都重新装配，不缓存</b>：配置表支持热更，缓存一份规则会让热更在社交这条路径上失效。
 * 而社交数值恰恰是最需要能热更的 —— 线上发现「联盟扩容太贵没人扩」，等一次发版再改是不可接受的。
 */
@Component
public class SocialRulesAssembler {

    private static final long MILLIS_PER_SECOND = 1000L;

    private final ConfigRegistry configs;

    public SocialRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    // ---------- 小队 ----------

    /** 小队规则。人数上限 5→8→10，第二档的门槛在队长主城等级上。 */
    public Squad.Rules squadRules() {
        List<SquadConfigCfg> rows = configs.all(SquadConfigCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("squad_config 表为空：算不出人数上限，小队功能无法装配");
        }
        List<Squad.LevelRule> levels = new ArrayList<>(rows.size());
        for (SquadConfigCfg row : rows) {
            levels.add(new Squad.LevelRule(
                    row.squadLevel(),
                    row.memberCap(),
                    row.unlockMainLevel(),
                    row.rallyCapacity(),
                    // DECIMAL 已由反序列化器转成定点，这里原样传（规矩 1）
                    row.helpSpeedBonus(),
                    row.shopUnlock()));
        }
        SquadConfigCfg first = firstLevel(rows);
        return new Squad.Rules(levels,
                first.unlockMainLevel(),
                first.unlockDayOffset(),
                configs.longParam("SQUAD_LEVEL_EXP_BASE"),
                configs.fixedParam("SQUAD_LEVEL_EXP_GROWTH"));
    }

    /** 小队集结规则。人数上限取 global.RALLY_MAX_SIZE_SQUAD，与 squad_config.rallyCapacity 一致。 */
    public Rally.Rules squadRallyRules() {
        return rallyRules("RALLY_MAX_SIZE_SQUAD");
    }

    // ---------- 联盟 ----------

    /** 联盟规则。人数上限 30→50→80→120→150，扩容消耗几何增长。 */
    public Alliance.Rules allianceRules() {
        List<AllianceConfigCfg> rows = configs.all(AllianceConfigCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("alliance_config 表为空：算不出人数上限，联盟功能无法装配");
        }
        List<Alliance.LevelRule> levels = new ArrayList<>(rows.size());
        for (AllianceConfigCfg row : rows) {
            levels.add(new Alliance.LevelRule(
                    row.allianceLevel(),
                    row.memberCap(),
                    row.unlockMainLevel(),
                    row.unlockDayOffset(),
                    row.territoryCap(),
                    row.rallyCapacity(),
                    // DECIMAL 已由反序列化器转成定点，这里原样传（规矩 1）
                    row.techCapBonus(),
                    row.donationDailyCap()));
        }
        AllianceConfigCfg first = firstAllianceLevel(rows);
        return new Alliance.Rules(levels,
                first.unlockMainLevel(),
                first.unlockDayOffset(),
                configs.longParam("ALLIANCE_CREATE_COST_GOLD"),
                // 秒 → 毫秒（规矩 3）
                configs.longParam("ALLIANCE_DISBAND_PROTECT_SECONDS") * MILLIS_PER_SECOND,
                configs.longParam("ALLIANCE_EXPAND_COST_BASE"),
                configs.fixedParam("ALLIANCE_EXPAND_COST_GROWTH"),
                donateTiers(),
                configs.fixedParam("ALLIANCE_TECH_COST_GROWTH"));
    }

    /** 联盟集结规则。 */
    public Rally.Rules allianceRallyRules() {
        return rallyRules("RALLY_MAX_SIZE_ALLIANCE");
    }

    /**
     * 捐献三档（免费 / 资源 / 金币）。
     *
     * <p>档位顺序与 B10 §2 一致：0 免费、1 资源、2 金币。
     * 资源档消耗的资源种类来自 global.DONATE_TIER_RESOURCE_TYPE ——
     * 它单独占一个参数而不是写死在这里，是为了让「资源档改成消耗木材」只需要改表。
     */
    private List<Alliance.DonateTier> donateTiers() {
        String resourceType = configs.stringParam("DONATE_TIER_RESOURCE_TYPE");
        return List.of(
                new Alliance.DonateTier(0, null, 0L, 0L,
                        configs.longParam("DONATE_TIER_FREE_FUND"),
                        configs.longParam("DONATE_TIER_FREE_CONTRIBUTION"),
                        donateExp(0)),
                new Alliance.DonateTier(1, resourceType,
                        configs.longParam("DONATE_TIER_RESOURCE_COST"), 0L,
                        configs.longParam("DONATE_TIER_RESOURCE_FUND"),
                        configs.longParam("DONATE_TIER_RESOURCE_CONTRIBUTION"),
                        donateExp(1)),
                new Alliance.DonateTier(2, null, 0L,
                        configs.longParam("DONATE_TIER_GOLD_COST"),
                        configs.longParam("DONATE_TIER_GOLD_FUND"),
                        configs.longParam("DONATE_TIER_GOLD_CONTRIBUTION"),
                        donateExp(2)));
    }

    /**
     * 捐献给的联盟经验。
     *
     * <p>取「该档产出的资金」作为经验值：资金是联盟的公共产出，用它当经验意味着
     * 「为盟里换来多少资源」直接等于「盟里成长了多少」，两条线不会互相拉扯。
     * 单独配一组 DONATE_TIER_*_EXP 也可以，但那会多三个需要与资金保持比例的参数 ——
     * 而任何需要「手工保持比例」的参数对，早晚有一天会被改得不成比例。
     */
    private long donateExp(int tier) {
        return switch (tier) {
            case 0 -> configs.longParam("DONATE_TIER_FREE_FUND");
            case 1 -> configs.longParam("DONATE_TIER_RESOURCE_FUND");
            case 2 -> configs.longParam("DONATE_TIER_GOLD_FUND");
            default -> throw new IllegalArgumentException("未知的捐献档位：" + tier);
        };
    }

    // ---------- 权限 / 聊天 / 互助 / 集结 ----------

    /**
     * 权限矩阵（B10 验收 4）。
     *
     * <p>role_permission 表的三个 allow* 列映射到 PermissionMatrix 的三档 tier。
     * scope 从配置枚举翻译成 core 枚举 —— 这是全项目唯一一处翻译点，
     * 与 {@code BattleRules.from} 把兵种名翻成 UnitType 是同一种做法。
     */
    public PermissionMatrix permissions() {
        List<PermissionMatrix.Grant> grants = new ArrayList<>();
        for (RolePermissionCfg row : configs.all(RolePermissionCfg.class)) {
            grants.add(new PermissionMatrix.Grant(
                    scopeOf(row.scope()), row.permission(),
                    row.allowLeader(), row.allowOfficer(), row.allowMember()));
        }
        return PermissionMatrix.of(grants);
    }

    /** 聊天限流规则（验收 9：同内容 10 秒内第 4 次被拦）。 */
    public ChatRateLimiter.Rules chatRules() {
        return new ChatRateLimiter.Rules(
                // 秒 → 毫秒（规矩 3）
                configs.longParam("CHAT_RATE_LIMIT_WINDOW_SECONDS") * MILLIS_PER_SECOND,
                (int) configs.longParam("CHAT_RATE_LIMIT_COUNT"),
                (int) configs.longParam("CHAT_LOCAL_HISTORY_MAX"));
    }

    /**
     * 互助帮助规则。
     *
     * <p>单次比例取 global.ALLIANCE_HELP_SPEED_BONUS 而不是 squad_config.helpSpeedBonus：
     * B10 §1 与 §2 都写「每次减 1%」，两个来源必须同值（SocialConfigTest 已断言），
     * 所以取哪个都一样 —— 取 global 是因为 HelpLedger 是跨小队/联盟共用的一个台账，
     * 而 global 是唯一不属于任何一方的参数源。
     */
    public HelpLedger.Rules helpRules() {
        return new HelpLedger.Rules(
                (int) configs.longParam("HELP_DAILY_LIMIT"),
                configs.fixedParam("HELP_SPEEDUP_TOTAL_CAP"),
                configs.fixedParam("ALLIANCE_HELP_SPEED_BONUS"));
    }

    /**
     * 集结规则。
     *
     * @param maxSizeParam 该层级的人数上限参数名（RALLY_MAX_SIZE_SQUAD / _ALLIANCE / _NATION）。
     *                     本方法不装配上限本身 —— 上限是「发起时传进来的 maxMembers」，
     *                     由调用方按层级取；这里只装配下限与准备时长区间
     */
    private Rally.Rules rallyRules(String maxSizeParam) {
        long maxSize = configs.longParam(maxSizeParam);
        if (maxSize < 1) {
            throw new IllegalStateException(maxSizeParam + " 必须为正，实际=" + maxSize);
        }
        return new Rally.Rules(
                (int) configs.longParam("RALLY_MIN_SIZE"),
                // 秒 → 毫秒（规矩 3）
                configs.longParam("RALLY_PREPARE_MIN_SECONDS") * MILLIS_PER_SECOND,
                configs.longParam("RALLY_PREPARE_MAX_SECONDS") * MILLIS_PER_SECOND);
    }

    /** 某层级的集结人数上限。发起集结时用它夹住客户端传来的 maxMembers。 */
    public int rallyMaxSize(Rally.Scope scope) {
        String param = switch (scope) {
            case SQUAD -> "RALLY_MAX_SIZE_SQUAD";
            case ALLIANCE -> "RALLY_MAX_SIZE_ALLIANCE";
            case NATION -> "RALLY_MAX_SIZE_NATION";
        };
        return (int) configs.longParam(param);
    }

    /** 被攻击推送的时间预算（验收 5）。埋点用它做报警阈值，见 global.ATTACK_PUSH_DEADLINE_MS 的 why。 */
    public long attackPushDeadlineMillis() {
        return configs.longParam("ATTACK_PUSH_DEADLINE_MS");
    }

    /** 每日小队任务的目标数与小队币产出（B10 §1、开放问题 3）。 */
    public SquadQuest squadQuest() {
        return new SquadQuest(
                configs.longParam("SQUAD_QUEST_DAILY_MONSTER"),
                configs.longParam("SQUAD_COIN_DAILY_QUEST"));
    }

    /** 每日小队任务参数。 */
    public record SquadQuest(long dailyMonsterTarget, long coinPerMember) {
    }

    // ---------- 内部 ----------

    private static PermissionMatrix.Scope scopeOf(RolePermissionCfg.Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("role_permission 行的 scope 不得为 null");
        }
        return switch (scope) {
            case SQUAD -> PermissionMatrix.Scope.SQUAD;
            case ALLIANCE -> PermissionMatrix.Scope.ALLIANCE;
            case NATION -> PermissionMatrix.Scope.NATION;
        };
    }

    /** 取等级最低的那一行（创建时的门槛在它身上）。 */
    private static SquadConfigCfg firstLevel(List<SquadConfigCfg> rows) {
        SquadConfigCfg first = null;
        for (SquadConfigCfg row : rows) {
            if (first == null || row.squadLevel() < first.squadLevel()) {
                first = row;
            }
        }
        if (first == null) {
            throw new IllegalStateException("squad_config 表为空");
        }
        return first;
    }

    private static AllianceConfigCfg firstAllianceLevel(List<AllianceConfigCfg> rows) {
        AllianceConfigCfg first = null;
        for (AllianceConfigCfg row : rows) {
            if (first == null || row.allianceLevel() < first.allianceLevel()) {
                first = row;
            }
        }
        if (first == null) {
            throw new IllegalStateException("alliance_config 表为空");
        }
        return first;
    }
}
