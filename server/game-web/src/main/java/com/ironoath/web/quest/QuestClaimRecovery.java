package com.ironoath.web.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.HeroCfg;
import com.ironoath.config.cfg.HeroRarityCfg;
import com.ironoath.config.cfg.ItemCfg;
import com.ironoath.core.quest.QuestProgress;
import com.ironoath.core.reward.FrozenQuestReward;
import com.ironoath.core.reward.GrantResult;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.battlepass.BattlePassService;

/** Only called under owner -> quest lock. Events and Guide projection reads must not execute this writer. */
final class QuestClaimRecovery {
    private final ConfigRegistry configs;
    private final QuestProgressStore store;
    private final RewardService rewards;
    private final BattlePassService battlePass;
    QuestClaimRecovery(ConfigRegistry configs, QuestProgressStore store, RewardService rewards, BattlePassService battlePass) {
        this.configs = configs; this.store = store; this.rewards = rewards; this.battlePass = battlePass;
    }
    QuestClaimPlan accept(String playerId, QuestProgress progress, String questId, List<RewardItem> items, long points, long now) {
        var entry = progress.entry(questId);
        String period = switch (entry.type()) {
            case DAILY -> "DAY:" + progress.dayKey();
            case WEEKLY -> "WEEK:" + progress.weekKey();
            default -> "ONCE";
        };
        long expires = Math.addExact(now, Math.multiplyExact(configs.longParam("MAIL_RETENTION_DAYS"), 86_400_000L));
        var plan = new QuestClaimPlan("quest_claim:" + questId + ":" + period, questId, period,
                items.stream().map(this::freeze).toList(), battlePass.activitySeasonId(), points,
                battlePass.activityTierSnapshot(), now, expires);
        var old = store.load(playerId).orElse(null);
        var pending = new ArrayList<>(old == null ? List.<QuestClaimPlan>of() : old.pendingClaims());
        if (pending.stream().anyMatch(p -> p.businessKey().equals(plan.businessKey())))
            throw new IllegalStateException("任务已有待恢复的领取计划");
        pending.add(plan);
        // Claimed projection and delivery promise must be the very same persistence action.
        store.save(playerId, new QuestProgressStore.State(playerId, progress.entries(), progress.dayKey(), progress.weekKey(), pending));
        return plan;
    }
    private FrozenQuestReward freeze(RewardItem item) {
        FrozenQuestReward.ItemConversion conversion = null;
        if (item.type() == RewardType.ITEM) conversion = item(item.id(), item.count());
        if (item.type() == RewardType.HERO || item.type() == RewardType.HERO_FRAGMENT) {
            var hero = configs.get(HeroCfg.class, item.id());
            long count = item.type() == RewardType.HERO_FRAGMENT ? item.count()
                    : configs.get(HeroRarityCfg.class, hero.rarity().name()).dupFragment();
            if (count > 0) conversion = item("item_mat_hero_frag_" + hero.rarity().name().toLowerCase(Locale.ROOT), count);
        }
        return new FrozenQuestReward(item, conversion);
    }
    private FrozenQuestReward.ItemConversion item(String id, long count) {
        var cfg = configs.get(ItemCfg.class, id);
        if (cfg.type() == ItemCfg.Type.EQUIP) throw new IllegalArgumentException("普通任务计划只接受堆叠材料");
        return new FrozenQuestReward.ItemConversion(id, count, cfg.stackMax());
    }
    void recover(String playerId) {
        var state = store.load(playerId).orElse(null);
        if (state != null) for (var plan : state.pendingClaims()) deliverAndFinish(playerId, plan);
    }
    GrantResult deliverAndFinish(String playerId, QuestClaimPlan plan) {
        var facts = Set.of("quest-mail-created:" + plan.acceptedAt(), "quest-mail-expires:" + plan.mailExpireAt());
        var result = rewards.grantQuest(playerId, plan.rewards(),
                new RewardContext("quest_claim", plan.questId(), plan.businessKey(), true, facts));
        if (plan.points() > 0) {
            battlePass.addQuestPointsOnce(playerId, plan.seasonId(), plan.businessKey(), plan.points());
            battlePass.recoverQuestTierRewards(playerId, plan.seasonId(), plan.battlePassTiers());
        }
        var state = store.load(playerId).orElseThrow(() -> new IllegalStateException("任务接受计划丢失"));
        store.save(playerId, new QuestProgressStore.State(playerId, state.entries(), state.dayKey(), state.weekKey(),
                state.pendingClaims().stream().filter(p -> !p.businessKey().equals(plan.businessKey())).toList()));
        return result;
    }
}
