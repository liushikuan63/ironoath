package com.ironoath.web.activity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.ActivityCfg;
import com.ironoath.core.activity.ActivityCondition;
import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.core.activity.ActivityType;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

/**
 * 职责：把 {@code activity.json} 的 8 行装配成 core 的活动定义与奖励表（B17 §一）。
 * 依赖：{@link ConfigRegistry}、core 的两个枚举、{@link RewardItem}。
 *
 * <p><b>两处映射都只写一次，并且让编译器盯着</b>：表里的 {@code activityType} /
 * {@code conditionType} 是<b>生成的嵌套枚举</b>（与 core 的枚举是两个类型），
 * 所以映射用 switch <b>表达式</b>——加一个表上的取值而忘了映射会直接编译不过，
 * 而不是在运行期变成"这一行永远用错锚点 / 永远订阅不到事件"。
 * （switch 语句不会给这个保证：它只是不完整地跳过。）
 *
 * <p><b>奖励为什么单独一张表而不是塞进 {@code ActivityProgress.Def}</b>：
 * 与任务系统同一条口径（见 {@code QuestProgress} 的类注释）—— 进度域只回答"完成了没有、领了没有"，
 * "发什么、发不下怎么办"是 B04 通用发放器的职责。抄一份进去就会出现两个家，
 * 而症状是"活动发的东西和面板上写的不一样"。
 */
@Component
public class ActivityRulesAssembler {

    private final ConfigRegistry configs;

    public ActivityRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    /** 全量活动定义（顺序 = 表序，服务端不另行排序）。 */
    public List<ActivityProgress.Def> activities() {
        List<ActivityProgress.Def> defs = new ArrayList<>();
        for (ActivityCfg row : configs.all(ActivityCfg.class)) {
            defs.add(new ActivityProgress.Def(row.id(), typeOf(row.activityType()),
                    conditionOf(row.conditionType()), row.conditionValue(), row.durationDays()));
        }
        return List.copyOf(defs);
    }

    /**
     * 每行活动达标后发什么。**从表里现算**（热更一次表就改行为，验收 1）：
     * 金币按 {@code RewardType.RESOURCE} + resource 表的 {@code GOLD} 行表达
     * （金币是资源的一种，B04 的五种资源里就有它）；道具按 {@code RewardType.ITEM}。
     */
    public Map<String, List<RewardItem>> rewardsByActivity() {
        Map<String, List<RewardItem>> out = new LinkedHashMap<>();
        for (ActivityCfg row : configs.all(ActivityCfg.class)) {
            List<RewardItem> rewards = new ArrayList<>(2);
            if (row.rewardGold() > 0L) {
                rewards.add(new RewardItem(RewardType.RESOURCE, GOLD_RESOURCE_ID, row.rewardGold()));
            }
            if (row.rewardItemId() != null && !row.rewardItemId().isBlank() && row.rewardItemCount() > 0L) {
                rewards.add(new RewardItem(RewardType.ITEM, row.rewardItemId(), row.rewardItemCount()));
            }
            if (rewards.isEmpty()) {
                // 一行没有任何奖励：发奖端会静默走完，而玩家点了领取什么都不发生。
                // 直接拒启动，理由与 game-config 的启动校验同一条
                throw new IllegalStateException("活动 " + row.id() + " 既没有金币也没有道具奖励："
                        + "这种行发奖时不会报错，玩家却什么都拿不到");
            }
            out.put(row.id(), List.copyOf(rewards));
        }
        return Map.copyOf(out);
    }

    /**
     * 活动 id → 名称。名字只在表里存一份：客户端硬编码一份就会出现"改表了但界面没改"
     * （与 {@code QuestView.name} 同一条口径 —— 服务端下发，客户端不做翻译）。
     */
    public Map<String, String> namesById() {
        Map<String, String> out = new LinkedHashMap<>();
        for (ActivityCfg row : configs.all(ActivityCfg.class)) {
            out.put(row.id(), row.name());
        }
        return Map.copyOf(out);
    }

    /** 金币在 resource 表里的行 id。写死在这里是错的，所以它明说是"表里的那个 id"而不是数值。 */
    private static final String GOLD_RESOURCE_ID = "GOLD";

    private static ActivityType typeOf(ActivityCfg.ActivityType type) {
        return switch (type) {
            case LOGIN_STREAK -> ActivityType.LOGIN_STREAK;
            case KILL_MONSTER -> ActivityType.KILL_MONSTER;
            case JOIN_RALLY -> ActivityType.JOIN_RALLY;
            case DONATE -> ActivityType.DONATE;
            case PVP_WIN -> ActivityType.PVP_WIN;
            case UPGRADE_BUILDING -> ActivityType.UPGRADE_BUILDING;
            case HELP_SQUAD -> ActivityType.HELP_SQUAD;
        };
    }

    private static ActivityCondition conditionOf(ActivityCfg.ConditionType condition) {
        return switch (condition) {
            case LOGIN_DAYS -> ActivityCondition.LOGIN_DAYS;
            case KILL_MONSTER_TOTAL -> ActivityCondition.KILL_MONSTER_TOTAL;
            case JOIN_RALLY_TOTAL -> ActivityCondition.JOIN_RALLY_TOTAL;
            case DONATE_TOTAL -> ActivityCondition.DONATE_TOTAL;
            case PVP_WIN_TOTAL -> ActivityCondition.PVP_WIN_TOTAL;
            case UPGRADE_COUNT -> ActivityCondition.UPGRADE_COUNT;
            case HELP_COUNT -> ActivityCondition.HELP_COUNT;
        };
    }
}
