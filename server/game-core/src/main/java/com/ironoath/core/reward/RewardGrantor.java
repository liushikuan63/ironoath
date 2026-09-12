package com.ironoath.core.reward;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：通用奖励发放器实现 —— B04 本批次的核心。
 * 依赖：仅 {@link RewardPorts} 里的四个端口（纯 Java，零框架、零数据库）。
 *
 * <p><b>尽力发放语义</b>：一条奖励溢出或失败不影响同批其它奖励。
 * 这与扣减的原子语义相反 —— 扣减要么全扣要么不扣，发放则要尽量让玩家拿到能拿的部分。
 * 理由是两者的失败后果不对称：少扣了资源可以补扣，少给了奖励玩家会直接投诉。
 *
 * <p>三段式结果，每一段都必须显式返回，不允许静默：
 * <ol>
 *   <li>{@code granted}：实际到账的部分</li>
 *   <li>{@code overflow}：因容量/堆叠上限被截断的部分 → 转邮件（除非 ctx 明确禁止）</li>
 *   <li>{@code compensationId}：抛异常的部分 → 进补偿队列</li>
 * </ol>
 *
 * <p>顺序保证：按入参顺序逐条发放，因此 {@code granted} 的顺序就是客户端飘字的播放顺序
 * （B04 §6 要求多个奖励排队播放、不可同时堆叠遮挡）。
 */
public final class RewardGrantor implements RewardService {

    private final RewardPorts.Wallet wallet;
    private final RewardPorts.Bag bag;
    private final RewardPorts.Mailbox mailbox;
    private final RewardPorts.Compensation compensation;
    private final RewardPorts.Extras extras;
    private final java.util.function.LongSupplier clock;

    /**
     * @param clock 服务端时间源（毫秒）。<b>必须由调用方注入</b>，
     *              game-core 里不允许出现 {@code System.currentTimeMillis()} ——
     *              那会让发放逻辑无法在单测里控制时间，也就无法验证惰性结算与过期判定。
     *              生产传入 {@code TimeService::serverNow}，单测传入可控的时间持有器。
     */
    public RewardGrantor(RewardPorts.Wallet wallet, RewardPorts.Bag bag, RewardPorts.Mailbox mailbox,
                         RewardPorts.Compensation compensation, RewardPorts.Extras extras,
                         java.util.function.LongSupplier clock) {
        if (wallet == null || bag == null || mailbox == null || compensation == null || extras == null) {
            throw new IllegalArgumentException("发放器的四个下游端口都不得为 null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("时间源不得为 null：game-core 不读系统时钟");
        }
        this.wallet = wallet;
        this.bag = bag;
        this.mailbox = mailbox;
        this.compensation = compensation;
        this.extras = extras;
        this.clock = clock;
    }

    @Override
    public GrantResult grant(String playerId, List<RewardItem> rewards, RewardContext ctx) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("RewardContext 不得为 null：没有来源的发奖无法归因");
        }
        if (rewards == null || rewards.isEmpty()) {
            return GrantResult.full(List.of());
        }

        long now = clock.getAsLong();
        List<RewardItem> granted = new ArrayList<>(rewards.size());
        List<RewardItem> overflow = new ArrayList<>();
        List<RewardItem> failed = new ArrayList<>();
        // 保留首个失败原因：补偿记录里只写「业务校验未通过」等于没写，
        // 客服与运维拿到补偿单却无法判断该补什么、为什么失败
        Throwable firstCause = null;

        for (RewardItem reward : rewards) {
            try {
                applyOne(playerId, reward, now, granted, overflow);
            } catch (RuntimeException e) {
                // 单条失败不中断整批：其余奖励照常发放，失败的进补偿队列
                failed.add(reward);
                if (firstCause == null) {
                    firstCause = e;
                }
            }
        }

        String mailId = null;
        if (!overflow.isEmpty() && ctx.overflowToMail()) {
            mailId = mailbox.sendOverflow(playerId, List.copyOf(overflow), ctx);
        }
        String compensationId = null;
        if (!failed.isEmpty()) {
            compensationId = compensation.record(playerId, List.copyOf(failed), ctx, firstCause);
        }
        return new GrantResult(granted, overflow, mailId, compensationId);
    }

    private void applyOne(String playerId, RewardItem reward, long now,
                          List<RewardItem> granted, List<RewardItem> overflow) {
        long requested = reward.count();
        long actual = switch (reward.type()) {
            // 体力是 resource 表里的一行（kind=STAMINA），所以走 Wallet。
            // Wallet 的实现已经保证「绝不加到超过上限」，那正是 B09 §5 的
            // 「溢出不累积超上限」；走 Extras 就得把同一条保证再实现一遍
            case RESOURCE, STAMINA -> wallet.grant(playerId, reward.id(), requested, now);
            case ITEM -> bag.add(playerId, reward.id(), requested);
            case HERO, HERO_FRAGMENT, PRIVILEGE ->
                    extras.grant(playerId, reward.type(), reward.id(), requested);
        };
        if (actual < 0L) {
            throw new IllegalStateException("下游返回了负的发放量，type=" + reward.type()
                    + ", id=" + reward.id() + ", actual=" + actual);
        }
        if (actual > 0L) {
            granted.add(reward.withCount(actual));
        }
        long rest = requested - actual;
        if (rest > 0L) {
            overflow.add(reward.withCount(rest));
        }
    }
}
