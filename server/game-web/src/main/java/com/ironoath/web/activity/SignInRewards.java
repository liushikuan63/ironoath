package com.ironoath.web.activity;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.SignInRewardCfg;
import com.ironoath.core.activity.SignInProgress;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.ActivityCategory;
import com.ironoath.web.dto.generated.ActivityClaimResp;
import com.ironoath.web.dto.generated.ActivityReward;
import com.ironoath.web.dto.generated.ActivityState;
import com.ironoath.web.dto.generated.ActivityView;
import com.ironoath.web.reward.RewardNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 复用活动入口；调用方必须持有玩家锁，客户端不提供日期、额度和资格事实。 */
@Service
public final class SignInRewards {
    private final ConfigRegistry configs;
    private final PlayerRepository players;
    private final RewardService rewards;
    private final RewardNames names;
    public SignInRewards(ConfigRegistry configs, PlayerRepository players, RewardService rewards, RewardNames names) {
        this.configs = configs; this.players = players; this.rewards = rewards; this.names = names;
    }

    private List<SignInProgress.Rule> rules() {
        return configs.all(SignInRewardCfg.class).stream().map(row -> new SignInProgress.Rule(
                row.id(), row.name(), SignInProgress.Period.valueOf(row.period().name()),
                SignInProgress.Metric.valueOf(row.metric().name()), row.targetDays(), row.allowedMissDays(), row.rewardGold())).toList();
    }
    public boolean contains(String id) { return rules().stream().anyMatch(rule -> rule.id().equals(id)); }
    public List<ActivityView> list(String playerId, long now) {
        return list(playerId, now, players.findByPlayerId(playerId));
    }

    /**
     * 复用调用方<b>已经读到的</b>存档：同一次请求里同一份存档只读一次。
     *
     * <p>为什么不给这口就白读两次：活动红点（{@code activity/claimable} 叶子）会在同一次请求里
     * 既让活动域读一次收据、又让签到域读一次同一份收据，而两次之间没有玩家存档写入。
     *
     * <p>⚠ <b>调用方必须保证这份存档是「补发之后」的</b>：{@code ActivityAppService.load} 先
     * {@code recoverGoldClaims}（可能补发并落库）才读存档 ⇒ 提前读到的会是过期收据。
     * 判据：{@code ActivityClaimableReadCountTest}。
     */
    public List<ActivityView> list(String playerId, long now, Optional<PlayerSave> owner) {
        if (owner.isEmpty()) return List.of();
        // 归属校验：这份存档必须是**这个玩家**的（调用方把 load 读到的 owner 传进来时必须同源）。
        // 用 IllegalArgumentException 而不是业务码：身份对不上是**编程错误**，不是玩家能触发的业务分支。
        if (!playerId.equals(owner.get().playerId())) {
            throw new IllegalArgumentException("存档与玩家不匹配：playerId=" + playerId);
        }
        var receipts = owner.get().goldClaims();
        var quote = SignInProgress.quote(now, rules(), receipts);
        List<ActivityView> out = new ArrayList<>();
        for (var row : quote.rows()) {
            boolean daily = row.rule().period() == SignInProgress.Period.DAILY;
            boolean ready = daily && !quote.awards().isEmpty();
            long amount = receipts.containsKey(row.businessKey())
                    ? receipts.get(row.businessKey()).requested() : row.rule().gold();
            String detail;
            if (daily && ready) {
                amount = quote.gold();
                detail = "本次可领" + amount + "金币：" + quote.awards().stream().map(award -> {
                    String label = quote.rows().stream().filter(r -> r.rule().id().equals(award.context().sourceRef()))
                            .findFirst().orElseThrow().rule().name();
                    return label + award.amount();
                }).collect(Collectors.joining("、")) + "。每日北京时间04:00刷新。";
            } else if (daily) {
                detail = "今日签到已领取；周/月达标奖励已随签到登记。北京时间04:00刷新。";
            } else {
                detail = (row.rule().period() == SignInProgress.Period.MONTHLY ? "本月累计" :
                        row.rule().metric() == SignInProgress.Metric.CONSECUTIVE_DAYS ? "本周最长连签" : "本周累计")
                        + "满" + row.goal() + "天，额外" + amount + "金币，随每日签到领取。";
            }
            if (row.pending()) detail += "奖励已登记，尚有金币等待补发。";
            out.add(new ActivityView(row.rule().id(), row.rule().name(), ready ? ActivityState.CLAIMABLE : ActivityState.RUNNING,
                    row.progress(), row.goal(), row.windowEndAt(), daily ? !ready : row.claimed(),
                    ActivityCategory.SIGN_IN, preview(amount), detail, !daily, row.pending(),null));
        }
        return List.copyOf(out);
    }
    public ActivityClaimResp claim(String playerId, String id, long now) {
        var rule = rules().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
        if (rule.period() != SignInProgress.Period.DAILY)
            throw new BizException(ErrorCode.ACTIVITY_NOT_CLAIMABLE, "请领取每日签到，连签与满勤奖励会一起到账。" );
        var owner = players.findByPlayerId(playerId).orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND, "玩家存档不存在"));
        var quote = SignInProgress.quote(now, rules(), owner.goldClaims());
        if (quote.awards().isEmpty())
            throw new BizException(ErrorCode.ACTIVITY_NOT_CLAIMABLE, "今日签到奖励已领取，请在北京时间04:00刷新后再领。" );
        var result = rewards.grantGoldBatch(playerId, quote.awards());
        long credited = result.granted().stream().mapToLong(RewardItem::count).sum();
        long overflow = result.overflow().stream().mapToLong(RewardItem::count).sum();
        String message = "签到已领取，金币入账" + credited + (overflow > 0 ? "，另" + overflow + "金币已转入邮箱。" : "。");
        List<ActivityReward> granted = result.granted().stream()
                .map(r -> new ActivityReward(r.type().name(), r.id(), r.count(), names.nameOf(r))).toList();
        return new ActivityClaimResp(true, granted, ActivityState.RUNNING, message);
    }
    private List<ActivityReward> preview(long gold) {
        var item = new RewardItem(RewardType.RESOURCE, "GOLD", gold);
        return List.of(new ActivityReward(item.type().name(), item.id(), gold, names.nameOf(item)));
    }
}
