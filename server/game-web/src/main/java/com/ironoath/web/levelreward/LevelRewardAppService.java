package com.ironoath.web.levelreward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.LevelRewardCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.resource.ResourceIds;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.dto.generated.LevelRewardClaimReq;
import com.ironoath.web.dto.generated.LevelRewardClaimResp;
import com.ironoath.web.dto.generated.LevelRewardItem;
import com.ironoath.web.dto.generated.LevelRewardListResp;
import com.ironoath.web.dto.generated.LevelRewardRow;
import com.ironoath.web.reward.RewardNames;

/**
 * 职责：等级奖励域的应用服务 —— 读领取视图 + 领取一级（收口清单 #829 三项裁决的落点）。
 * 依赖：配置表 {@code level_reward}、玩家存档（只为读主城等级）、通用发放器、幂等键、玩家锁。
 *
 * <p><b>唯一入账入口是 {@link #claim}</b>。裁决②明确否掉了「升级到即发」，所以本类<b>不订阅任何升级事件</b>、
 * 也没有「读的时候顺手补发」的路径：主城升级只改变某一行的 locked/claimable，钱不会自己进账。
 * 少这一句约束，下一个人就会在 QuestEventListener 那类地方加一条「达到就发」，两条路并存之后
 * 「已领」账本与真实入账开始分叉，而分叉的症状是玩家看到两笔到账或一笔都没有。
 *
 * <p><b>表里的数值不抄进代码</b>：每级给多少全部现读 {@code level_reward}（铁律 1），
 * 于是「改一档奖励」是改表而不是发版；而「奖励与造价的同比例口径」不是靠表注释维持的，
 * 由 {@code LevelRewardTableTest} 每轮拿 Formula 现算造价去比这张表 —— curve 一动，那条比值判据就红。
 *
 * <p><b>失败方向选「标记了已领但没发出去」</b>（与 {@code QuestAppService.claim} 同一条取舍）：
 * 发放器有补偿队列与客服入口，而反过来（发了没标记）玩家能重复领 —— 后者没有任何补救手段。
 */
@Service
public class LevelRewardAppService {

    private static final Logger LOG = LoggerFactory.getLogger(LevelRewardAppService.class);
    private static final long LOCK_TIMEOUT_MS = 3000L;

    private final ConfigRegistry configs;
    private final TimeService timeService;
    private final RewardService rewardService;
    private final IdempotencyStore idempotency;
    private final PlayerRepository players;
    private final PlayerLock playerLock;
    private final RewardNames names;
    private final LevelRewardClaimStore claims;

    public LevelRewardAppService(ConfigRegistry configs, TimeService timeService,
                                 RewardService rewardService, IdempotencyStore idempotency,
                                 PlayerRepository players, PlayerLock playerLock,
                                 RewardNames names, LevelRewardClaimStore claims) {
        this.configs = configs;
        this.timeService = timeService;
        this.rewardService = rewardService;
        this.idempotency = idempotency;
        this.players = players;
        this.playerLock = playerLock;
        this.names = names;
        this.claims = claims;
    }

    /** 领取视图：全部等级行 + 此刻可领数 + 主城等级。纯读，不写任何状态。 */
    public LevelRewardListResp list(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
            int mainLevel = requirePlayer(playerId).cityLevel();
            Rendered rendered = render(mainLevel, loaded(playerId));
            return new LevelRewardListResp(rendered.rows(), rendered.claimableCount(), mainLevel, now);
        });
    }

    /**
     * 此刻可领的等级数（红点用）。
     *
     * <p>单独开这一口的理由与 {@code QuestAppService.claimableCount} 一样：红点在每帧都要问一次
     * 「有没有能领的」，为这一个布尔把 40 行连名字带奖励渲染出来是白花的服务端预算。
     * 两个值走同一个 {@link #render}，不允许各算一遍 —— 否则「列表里有 3 行可领而红点亮 2」
     * 这种症状迟早出现。
     */
    public int claimableCount(String playerId) {
        long now = timeService.serverNow();
        return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS,
                () -> render(requirePlayer(playerId).cityLevel(), loaded(playerId)).claimableCount());
    }

    /** 领取一级的奖励。未达等级 / 已领过 / 表里没这一级，各有各的业务码。 */
    public LevelRewardClaimResp claim(String playerId, LevelRewardClaimReq req) {
        long now = timeService.serverNow();
        if (req == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求体不得为空");
        }
        acquire(req.requestId(), now);
        try {
            return playerLock.runLocked(playerId, LOCK_TIMEOUT_MS, () -> {
                int mainLevel = requirePlayer(playerId).cityLevel();
                LevelRewardCfg row = rowOf(req.level());
                LevelRewardClaimStore.State state = loaded(playerId);
                if (row.level() > mainLevel) {
                    throw new BizException(ErrorCode.LEVEL_REWARD_NOT_REACHED,
                            "主城 " + mainLevel + " 级，还没到 " + row.level() + " 级");
                }
                if (state.claimed(row.level())) {
                    throw new BizException(ErrorCode.LEVEL_REWARD_ALREADY_CLAIMED,
                            "等级 " + row.level() + " 已经领过");
                }
                // 这两条的先后在所有可达存档上给出同一个答复（领过一次就说明当时已到级，而主城等级不会倒退），
                // 写在这里是为了下一个人不必为了「该先判哪个」再推一遍 —— 唯一能分出先后的场景是存档被人为回滚，
                // 那时给「还没到这一级」比给「已经领过」更贴近玩家看到的界面。
                List<RewardItem> rewards = rewardsOf(row);
                claims.save(playerId, state.withClaimed(row.level()));
                List<RewardItem> granted = List.of();
                if (!rewards.isEmpty()) {
                    var result = rewardService.grant(playerId, rewards,
                            RewardContext.toMail("level_reward", "lv" + row.level(),
                                    row.level() + ":" + req.requestId()));
                    granted = result.granted();
                    if (result.hasCompensation()) {
                        LOG.error("【等级奖励未入账已进补偿队列】playerId={} level={} 奖励={} compensationId={}",
                                playerId, row.level(), rewards, result.compensationId());
                    }
                }
                int remaining = render(mainLevel, loaded(playerId)).claimableCount();
                LOG.info("等级奖励已领取 playerId={} level={} 奖励={} 剩余可领={}",
                        playerId, row.level(), granted, remaining);
                return new LevelRewardClaimResp(row.level(), itemsOf(granted), remaining, now);
            });
        } catch (RuntimeException e) {
            idempotency.release(req.requestId());
            throw e;
        }
    }

    // ---------- 内部 ----------

    /** 一次遍历同时产出行视图与可领数（两个值必须同源，见 {@link #claimableCount}）。 */
    private Rendered render(int mainLevel, LevelRewardClaimStore.State state) {
        List<LevelRewardRow> rows = new ArrayList<>();
        int claimable = 0;
        for (LevelRewardCfg row : tableRows()) {
            boolean claimed = state.claimed(row.level());
            boolean locked = row.level() > mainLevel;
            boolean canClaim = !claimed && !locked;
            if (canClaim) {
                claimable++;
            }
            rows.add(new LevelRewardRow(row.level(), row.name(), itemsOf(rewardsOf(row)),
                    locked, canClaim, claimed));
        }
        return new Rendered(List.copyOf(rows), claimable);
    }

    /** 表行按等级升序（下发顺序由服务端定死，客户端不再排）。 */
    private List<LevelRewardCfg> tableRows() {
        return configs.all(LevelRewardCfg.class).stream()
                .sorted(Comparator.comparingLong(LevelRewardCfg::level))
                .toList();
    }

    private LevelRewardCfg rowOf(long level) {
        for (LevelRewardCfg row : tableRows()) {
            if (row.level() == level) {
                return row;
            }
        }
        throw new BizException(ErrorCode.LEVEL_REWARD_NOT_FOUND, "等级 " + level + " 在表里没有对应行");
    }

    /** 一行的奖励清单：表里三列逐列展开，0 的列不产出条目（本表 40 行都有木石，金币按档给）。 */
    private List<RewardItem> rewardsOf(LevelRewardCfg row) {
        List<RewardItem> rewards = new ArrayList<>(3);
        addResource(rewards, ResourceIds.WOOD, row.rewardWood());
        addResource(rewards, ResourceIds.STONE, row.rewardStone());
        addResource(rewards, ResourceIds.GOLD, row.rewardGold());
        return List.copyOf(rewards);
    }

    private static void addResource(List<RewardItem> out, String resourceId, long count) {
        if (count > 0L) {
            out.add(new RewardItem(RewardType.RESOURCE, resourceId, count));
        }
    }

    /** 奖励明细的协议视图。<b>名字一律由服务端解析</b>（客户端不抄配置表），id 只作数据不作展示。 */
    private List<LevelRewardItem> itemsOf(List<RewardItem> items) {
        List<LevelRewardItem> out = new ArrayList<>(items.size());
        for (RewardItem item : items) {
            out.add(new LevelRewardItem(item.type().name(), item.id(), item.count(), names.nameOf(item)));
        }
        return List.copyOf(out);
    }

    private LevelRewardClaimStore.State loaded(String playerId) {
        return claims.load(playerId).orElseGet(() -> LevelRewardClaimStore.State.empty(playerId));
    }

    private PlayerSave requirePlayer(String playerId) {
        Optional<PlayerSave> save = players.findByPlayerId(playerId);
        return save.orElseThrow(() -> new BizException(ErrorCode.PLAYER_NOT_FOUND,
                "玩家不存在：" + playerId));
    }

    /** 幂等键：与任务/活动/社交同一条（缺键回 1003，重复回 1002，TTL 读配置表）。 */
    private void acquire(String requestId, long now) {
        if (requestId == null || requestId.isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "领取等级奖励必须带 requestId");
        }
        long ttlMs = configs.longParam("REQUEST_ID_TTL_SECONDS") * 1000L;
        if (!idempotency.tryAcquire(requestId, now, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + requestId);
        }
    }

    private record Rendered(List<LevelRewardRow> rows, int claimableCount) {
    }
}
