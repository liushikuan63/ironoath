package com.ironoath.web.reward;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerResourceState;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.city.ResourceSettlement;

/**
 * 职责：把 {@link RewardPorts.Wallet} 端口适配到真实的玩家存档（PlayerSave + PlayerRepository）。
 * 依赖：game-core 的端口与惰性结算、game-core 的玩家仓储。
 *
 * <p><b>这个类是「RewardService 不得直接操作数据库」这条禁止项的正面落地</b>：
 * 发放器只认 Wallet 接口，而 Wallet 的实现走的是资源模块自己的惰性结算与上限校验。
 * 于是「发奖」与「建造扣资源」「产出结算」共用同一套上限与结算规则，不会分叉。
 *
 * <p>调用方必须在<b>玩家锁内</b>使用本类：它做的是「读档 → 改 → 写档」，
 * 没有锁就会有并发覆盖。CityAppService 与后续的 RewardAppService 都在锁内调用。
 *
 * <p>每次操作都独立读写存档（而不是缓存一份），是因为一次发奖可能同时改多种资源，
 * 而乐观锁版本在每次 save 后都会前进 —— 缓存 PlayerSave 会导致第二次 save 版本不匹配。
 */
public final class PlayerWallet implements RewardPorts.Wallet {

    private final PlayerRepository players;

    public PlayerWallet(PlayerRepository players) {
        if (players == null) {
            throw new IllegalArgumentException("PlayerRepository 不得为 null");
        }
        this.players = players;
    }

    @Override
    public RewardPorts.Wallet.GoldGrant grantGoldOnce(String playerId, long amount,
            com.ironoath.core.reward.RewardContext ctx, long now) {
        return grantGoldBatchOnce(playerId, java.util.List.of(new RewardPorts.Wallet.GoldRequest(amount, ctx)), now).get(0);
    }

    @Override
    public java.util.List<RewardPorts.Wallet.GoldGrant> grantGoldBatchOnce(String playerId,
            java.util.List<RewardPorts.Wallet.GoldRequest> requests, long now) {
        if (requests == null || requests.isEmpty()) throw new IllegalArgumentException("领取组成不得为空");
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var request : requests) {
            var ctx = request.context();
            if (request.amount() <= 0 || ctx == null || !ctx.durableGold() || !ctx.overflowToMail()
                    || !keys.add(ctx.traceId()))
                throw new IllegalArgumentException("整批金币领取必须有正额度、邮件策略和不重复的业务key");
        }
        PlayerSave save = require(playerId);
        PlayerResourceState state = save.resource("GOLD");
        ResourceSettlement.Result settled = ResourceSettlement.settle(
                state.current(), state.cap(), state.perHour(), state.lastSettle(), now);
        long current = settled.current(); boolean dirty = false;
        java.util.List<RewardPorts.Wallet.GoldGrant> accepted = new java.util.ArrayList<>();
        for (var request : requests) {
            var ctx = request.context(); var previous = save.goldClaims().get(ctx.traceId());
            if (previous != null) {
                if (!previous.source().equals(ctx.source()) || !previous.sourceRef().equals(ctx.sourceRef()))
                    throw new IllegalStateException("金币业务键不能复用于不同奖励来源");
                accepted.add(new RewardPorts.Wallet.GoldGrant(previous, 0));
                continue;
            }
            long credited = Math.min(request.amount(), state.cap() - current);
            var receipt = new com.ironoath.core.reward.GoldClaimReceipt(ctx.traceId(), ctx.source(),
                    ctx.sourceRef(), request.amount(), credited, now, ctx.claimFacts(), null);
            save.putGoldClaim(receipt); current += credited; dirty = true;
            accepted.add(new RewardPorts.Wallet.GoldGrant(receipt, credited));
        }
        if (dirty) {
            save.putResource("GOLD", new PlayerResourceState(current, state.cap(),
                    state.protectedAmount(), state.perHour(), settled.lastSettle()));
            players.save(save);
        }
        return java.util.List.copyOf(accepted);
    }

    @Override
    public java.util.List<com.ironoath.core.reward.GoldClaimReceipt> pendingGoldClaims(String playerId) {
        return require(playerId).goldClaims().values().stream()
                .filter(com.ironoath.core.reward.GoldClaimReceipt::pendingOverflow).toList();
    }

    @Override
    public boolean hasGoldClaim(String playerId, String businessKey) {
        return require(playerId).goldClaims().containsKey(businessKey);
    }

    @Override
    public void completeGoldOverflow(String playerId, String businessKey, String mailId) {
        PlayerSave save = require(playerId);
        var receipt = save.goldClaims().get(businessKey);
        if (receipt == null) throw new IllegalStateException("金币收据不存在");
        if (receipt.overflowMailId() != null) {
            if (!receipt.overflowMailId().equals(mailId)) throw new IllegalStateException("溢出邮件不能改号");
            return;
        }
        save.putGoldClaim(receipt.withOverflowMail(mailId));
        players.save(save);
    }

    @Override
    public ResourceGrant grantQuestResourceOnce(String playerId, com.ironoath.core.reward.RewardItem reward,
            com.ironoath.core.reward.RewardContext ctx, long now) {
        if (ctx == null || !ctx.durableQuest() || !ctx.overflowToMail())
            throw new IllegalArgumentException("任务资源需要稳定领取上下文");
        return acceptResourceOnce(playerId, reward, ctx, now);
    }
    @Override
    public ResourceGrant grantMailResourceOnce(String playerId, com.ironoath.core.reward.RewardItem reward,
            com.ironoath.core.reward.RewardContext ctx, long now) {
        if (ctx == null || !ctx.durableMailResources() || !ctx.overflowToMail() || "GOLD".equals(reward.id()))
            throw new IllegalArgumentException("普通资源邮件不能借用金币或任务身份");
        return acceptResourceOnce(playerId, reward, ctx, now);
    }
    private ResourceGrant acceptResourceOnce(String playerId, com.ironoath.core.reward.RewardItem reward,
            com.ironoath.core.reward.RewardContext ctx, long now) {
        PlayerSave save = require(playerId);
        var previous = save.resourceClaims().get(ctx.traceId());
        if (previous != null) {
            if (!previous.reward().equals(reward)) throw new IllegalStateException("任务资源业务键不能改变奖励");
            return new ResourceGrant(previous, 0);
        }
        var state = save.resource(reward.id());
        var settled = ResourceSettlement.settle(state.current(), state.cap(), state.perHour(), state.lastSettle(), now);
        long credited = Math.min(reward.count(), state.cap() - settled.current());
        var receipt = new com.ironoath.core.reward.ResourceClaimReceipt(ctx.traceId(), reward, credited, now, null);
        save.putResource(reward.id(), new PlayerResourceState(Math.addExact(settled.current(), credited),
                state.cap(), state.protectedAmount(), state.perHour(), settled.lastSettle()));
        save.putResourceClaim(receipt);
        players.save(save);
        return new ResourceGrant(receipt, credited);
    }

    @Override
    public void completeResourceOverflow(String playerId, String key, String mailId) {
        var save = require(playerId);
        var receipt = save.resourceClaims().get(key);
        if (receipt == null) throw new IllegalStateException("任务资源收据不存在");
        if (mailId.equals(receipt.overflowMailId())) return;
        save.putResourceClaim(receipt.withOverflowMail(mailId));
        players.save(save);
    }

    @Override
    public long grant(String playerId, String resourceType, long amount, long now) {
        if (amount < 0L) {
            throw new IllegalArgumentException("发放量不得为负：" + amount);
        }
        if (amount == 0L) {
            return 0L;
        }
        PlayerSave save = require(playerId);
        PlayerResourceState state = save.resource(resourceType);
        // 先做惰性结算再入账：否则玩家挂机期间攒下的产量会被这次发放覆盖掉
        ResourceSettlement.Result settled = ResourceSettlement.settle(
                state.current(), state.cap(), state.perHour(), state.lastSettle(), now);
        long next = Math.min(state.cap(), settled.current() + amount);
        save.putResource(resourceType, new PlayerResourceState(
                next, state.cap(), state.protectedAmount(), state.perHour(), settled.lastSettle()));
        players.save(save);
        // 返回实际入账量：超出容量的部分由发放器转邮件（B04 验收 2）
        return next - settled.current();
    }

    @Override
    public long available(String playerId, String resourceType, long now) {
        return available(require(playerId), resourceType, now);
    }

    /**
     * 余额（调用方<b>已经拿着</b>存档时用它）：不为一个余额再搬回整份存档。
     *
     * <p>与 {@link #available(String, String, long)} **逐字同口径**（同一套结算式、同样不落库），
     * 差别只在「档从哪来」。
     *
     * <p>⚠ 只读：{@code available} 不落库，而 {@code deduct} / {@code grant} 会
     * {@code players.save} 整篇 —— 那条路上**不能**传旧档（整篇 $set 会回滚并发写手的字段）。
     *
     * <p>判据：{@code StageListSinglePlayerReadTest}。
     */
    public long available(PlayerSave save, String resourceType, long now) {
        PlayerResourceState state = save.resource(resourceType);
        return ResourceSettlement.settle(
                state.current(), state.cap(), state.perHour(), state.lastSettle(), now).current();
    }

    @Override
    public long capacity(String playerId, String resourceType, long now) {
        return require(playerId).resource(resourceType).cap();
    }

    @Override
    public long protectedAmount(String playerId, String resourceType, long now) {
        return require(playerId).resource(resourceType).protectedAmount();
    }

    @Override
    public long deduct(String playerId, String resourceType, long amount, long now) {
        if (amount < 0L) {
            throw new IllegalArgumentException("扣减量不得为负：" + amount);
        }
        if (amount == 0L) {
            return 0L;
        }
        PlayerSave save = require(playerId);
        PlayerResourceState state = save.resource(resourceType);
        ResourceSettlement.Result settled = ResourceSettlement.settle(
                state.current(), state.cap(), state.perHour(), state.lastSettle(), now);
        if (settled.current() < amount) {
            // 不足则完全不扣：扣一半会让玩家处于「资源没了但东西也没拿到」的状态
            return 0L;
        }
        save.putResource(resourceType, new PlayerResourceState(
                settled.current() - amount, state.cap(), state.protectedAmount(),
                state.perHour(), settled.lastSettle()));
        players.save(save);
        return amount;
    }

    private PlayerSave require(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        return players.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("玩家存档不存在: " + playerId));
    }
}
