package com.ironoath.web.quest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.QuestCfg;
import com.ironoath.core.quest.GoalType;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardType;

/**
 * 职责：把 quest 表装配成任务定义与奖励（B12 §1，铁律 1：数值零硬编码）。
 * 依赖：game-config、game-core。
 *
 * <p>与其它装配器同一条：{@code game-core} 读不到配置表，所以 {@link QuestProgress.Def}
 * 与奖励清单都必须由外层解析好再传进去；每次调用都重新装配（配置支持热更）。
 *
 * <p><b>碎片奖励要落成「按稀有度的碎片道具」，而发放器要的是武将 id</b>：
 * quest 表写的是「几个碎片 + 哪一档稀有度」（{@code rewardFragmentRarity}，2026-09-11 的裁决），
 * 而 {@code RewardType.HERO_FRAGMENT} 的 id 约定是**武将 id**（发放器再用它推稀有度、
 * 落到 {@code item_mat_hero_frag_<rarity>}）。所以这里给每个稀有度挑一个**载体武将**：
 * 落库的是稀有度对应的碎片道具，不记在某个武将头上，所以取谁都不影响结果 ——
 * 但绝不能因此自己拼道具 id（那会把「稀有度 → 道具名」这条映射变成两个家，
 * 而拼错的表现是玩家拿到另一档的碎片，正如 {@code HeroFragmentExtras} 的注释所警告的）。
 */
@Component
public class QuestRulesAssembler {

    /** 装配好的一条任务：领域定义 + 表里的名字 + 领奖时要发的奖励。 */
    public record QuestDef(QuestProgress.Def def, String name, List<RewardItem> rewards,
                           List<String> heroChoices) {
        public QuestDef {
            if (def == null || name == null || name.isBlank() || rewards == null) {
                throw new IllegalArgumentException("QuestDef 的 def / name / rewards 都不得为空");
            }
            rewards = List.copyOf(rewards);
            heroChoices = heroChoices == null ? List.of() : List.copyOf(heroChoices);
        }

        /** 这条任务的奖励里有没有「让玩家挑一名武将」这一项。 */
        public boolean hasHeroChoice() {
            return !heroChoices.isEmpty();
        }
    }

    private final ConfigRegistry configs;

    public QuestRulesAssembler(ConfigRegistry configs) {
        this.configs = configs;
    }

    /**
     * 全部任务定义，按表的行序（主线的章节顺序就是表的顺序，客户端不需要再排）。
     *
     * <p>前置引用会在这里校验：{@code preQuest} 指向一条不存在的任务时当场抛 ——
     * 那个任务会永远处于「前置未完成」，而玩家看到的是一个永远锁着的任务，
     * 没有任何线索说明为什么（这正是「表里的一处笔误变成玩法卡死」的形状）。
     */
    public List<QuestDef> quests() {
        List<QuestCfg> rows = configs.all(QuestCfg.class);
        if (rows.isEmpty()) {
            throw new IllegalStateException("quest 表为空：装不出任何任务，任务面板会是一片空白");
        }
        Map<String, String> carrierByRarity = fragmentCarriers();
        List<QuestDef> out = new ArrayList<>(rows.size());
        Map<String, Boolean> ids = new HashMap<>();
        for (QuestCfg row : rows) {
            ids.put(row.id(), Boolean.TRUE);
        }
        for (QuestCfg row : rows) {
            QuestProgress.Def def = new QuestProgress.Def(row.id(), typeOf(row.questType()),
                    goalOf(row.goalType()), row.goalTarget(), row.goalValue(), row.preQuest());
            if (def.preQuestId() != null && !ids.containsKey(def.preQuestId())) {
                throw new IllegalStateException("任务 " + row.id() + " 的前置 " + def.preQuestId()
                        + " 不在 quest 表里：它永远无法解锁，而玩家只会看到一个锁死的任务");
            }
            out.add(new QuestDef(def, row.name(), rewardsOf(row, carrierByRarity),
                    heroChoicesOf(row)));
        }
        return List.copyOf(out);
    }

    /** 某个任务 id → 奖励清单。领取时按它发放。 */
    public Map<String, List<RewardItem>> rewardsByQuest() {
        Map<String, List<RewardItem>> out = new LinkedHashMap<>();
        for (QuestDef quest : quests()) {
            out.put(quest.def().questId(), quest.rewards());
        }
        return Map.copyOf(out);
    }

    // ---------- 内部 ----------

    private static QuestProgress.QuestType typeOf(QuestCfg.QuestType type) {
        if (type == null) {
            throw new IllegalStateException("quest 表的 questType 不得为空");
        }
        return QuestProgress.QuestType.valueOf(type.name());
    }

    private static GoalType goalOf(QuestCfg.GoalType goal) {
        if (goal == null) {
            throw new IllegalStateException("quest 表的 goalType 不得为空");
        }
        return GoalType.valueOf(goal.name());
    }

    /** 资源与碎片奖励都取自表列；全为 0 的行返回空清单（那种任务只做引导，不给东西）。 */
    private List<RewardItem> rewardsOf(QuestCfg row, Map<String, String> carrierByRarity) {
        List<RewardItem> rewards = new ArrayList<>(5);
        addResource(rewards, ResourceIds.GOLD, row.rewardGold());
        addResource(rewards, ResourceIds.WOOD, row.rewardWood());
        addResource(rewards, ResourceIds.IRON, row.rewardIron());
        addResource(rewards, ResourceIds.GRAIN, row.rewardGrain());
        if (row.rewardHeroId() != null && !row.rewardHeroId().isBlank()) {
            // 固定赠送（单值）：直接给这名武将。与候选组互斥，理由见表头 designNote
            if (!heroChoicesOf(row).isEmpty()) {
                throw new IllegalStateException("任务 " + row.id() + " 同时写了 rewardHeroId 与"
                        + " rewardHeroChoices：同一份奖励既指定又让挑，语义无从判断");
            }
            rewards.add(new RewardItem(RewardType.HERO, row.rewardHeroId(), 1L));
        }
        if (row.rewardHeroFragment() > 0L) {
            if (row.rewardFragmentRarity() == null) {
                throw new IllegalStateException("任务 " + row.id() + " 给了 " + row.rewardHeroFragment()
                        + " 个碎片却没写哪一档（rewardFragmentRarity）：发碎片必须指定稀有度");
            }
            String carrier = carrierByRarity.get(row.rewardFragmentRarity().name());
            if (carrier == null) {
                throw new IllegalStateException("hero 表里没有任何 " + row.rewardFragmentRarity()
                        + " 档武将，任务 " + row.id() + " 的碎片无处安放");
            }
            rewards.add(new RewardItem(RewardType.HERO_FRAGMENT, carrier, row.rewardHeroFragment()));
        }
        return rewards;
    }

    /**
     * 候选武将 id（三选一那类奖励）。
     *
     * <p><b>为什么是逗号分隔的字符串而不是新表</b>：候选是一份有序的短名单（本轮是 3 个），
     * 为它开一张关联表要同时付出「生成物、外键校验、装配器三处改动」，而收益只是把逗号换个形状。
     * 但<b>校验必须做足</b>：每个 id 都要真在 hero 表里（写错的表现是玩家点了那个选项之后
     * 领不到武将，而错误发生在领奖那一刻 —— 那时玩家已经点了按钮）。
     *
     * <p>候选数量下限 2：只写一个候选的「选择」是假选择，不如直接用 {@code rewardHeroId}。
     */
    private List<String> heroChoicesOf(QuestCfg row) {
        String raw = row.rewardHeroChoices();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(3);
        for (String part : raw.split(",")) {
            String heroId = part.trim();
            if (heroId.isEmpty()) {
                continue;
            }
            try {
                configs.get(HeroCfg.class, heroId);
            } catch (RuntimeException e) {
                throw new IllegalStateException("任务 " + row.id() + " 的候选武将 " + heroId
                        + " 不在 hero 表里：玩家选中它时会领不到武将，而那时他已经点了按钮");
            }
            out.add(heroId);
        }
        if (out.size() == 1) {
            throw new IllegalStateException("任务 " + row.id() + " 只写了一个候选武将（"
                    + out.get(0) + "）：只有一个选项的「选择」是假选择，请改用 rewardHeroId");
        }
        return List.copyOf(out);
    }

    private static void addResource(List<RewardItem> rewards, String resourceId, long count) {
        if (count > 0L) {
            rewards.add(new RewardItem(RewardType.RESOURCE, resourceId, count));
        }
    }

    /**
     * 每个稀有度挑一个载体武将（hero 表里第一个该档的）。见类注释：
     * 碎片落的是按稀有度的道具，载体只用来让发放器推出那一档。
     */
    private Map<String, String> fragmentCarriers() {
        Map<String, String> carriers = new HashMap<>();
        for (HeroCfg hero : configs.all(HeroCfg.class)) {
            carriers.putIfAbsent(hero.rarity().name().toUpperCase(Locale.ROOT), hero.id());
        }
        return carriers;
    }
}
