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
        PlayerSave save = require(playerId);
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
