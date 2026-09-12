package com.ironoath.web.reward;

import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import com.ironoath.core.reward.RewardType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 职责：邮箱、补偿队列、非资源非道具奖励这三个端口的<b>过渡实现</b>。
 * 依赖：game-core 的端口（纯 Java + slf4j）。
 *
 * <p>⚠️ <b>三者都还不是生产可用的实现</b>，各自的缺口与归属批次写在下面。
 * 之所以现在就接上而不是留空：发放器的三段式结果（granted / overflow / compensationId）
 * 必须能被端到端验证，否则 B04 的核心逻辑等于没测过。
 *
 * <h2>缺口清单</h2>
 * <ol>
 *   <li>{@link TransientMailbox}：溢出邮件只在内存与日志里，<b>重启即丢</b>。
 *       B12（邮件系统）必须换成持久化实现，否则玩家会因为一次背包满而永久损失奖励。</li>
 *   <li>{@link TransientCompensation}：补偿记录同样只在内存与日志里。
 *       B12 必须换成持久化 + 可人工重放的实现 —— 这是「发奖失败不静默」的最后兜底，
 *       丢了就等于把玩家的投诉变成无据可查。</li>
 *   <li>{@link UnsupportedExtras}：武将碎片 / 体力 / 特权<b>故意抛异常</b>。
 *       抛异常会被发放器捕获并写进补偿队列，这是设计好的路径 ——
 *       未实现的奖励类型必须<b>响亮地失败</b>并进补偿，而不是静默返回「发放成功」。
 *       后者会让玩家看到「已获得 SSR 碎片 ×5」但账户里什么都没有。
 *       B06（武将）/ B09（体力）/ B03（队列特权）各自落地后替换对应分支。</li>
 * </ol>
 *
 * <p>三者在启动时都会打 ERROR 级日志，确保任何人跑起服务端都会看到这些缺口。
 */
public final class TransientRewardPorts {

    private TransientRewardPorts() {
    }

    /** 溢出补发邮件的内存实现。 */
    public static final class TransientMailbox implements RewardPorts.Mailbox {

        private static final Logger LOG = LoggerFactory.getLogger(TransientMailbox.class);
        private final AtomicLong seq = new AtomicLong();
        private final Map<String, List<RewardItem>> pending = new ConcurrentHashMap<>();

        @Override
        public String sendOverflow(String playerId, List<RewardItem> overflow, RewardContext ctx) {
            String mailId = "mail_overflow_" + seq.incrementAndGet();
            pending.put(mailId, overflow);
            // ERROR 级：这条日志的存在本身就是提醒「邮件还没持久化」
            LOG.error("【未持久化】溢出奖励转邮件 playerId={} mailId={} source={} 溢出明细={} "
                            + "—— 本实现重启即丢，B12 邮件系统必须替换",
                    playerId, mailId, ctx.source(), overflow);
            return mailId;
        }

        /** 供测试与后续 B12 迁移使用：查看待发邮件。 */
        public Map<String, List<RewardItem>> pending() {
            return Map.copyOf(pending);
        }
    }

    /** 补偿队列的内存实现。 */
    public static final class TransientCompensation implements RewardPorts.Compensation {

        private static final Logger LOG = LoggerFactory.getLogger(TransientCompensation.class);
        private final AtomicLong seq = new AtomicLong();
        private final Map<String, List<RewardItem>> pending = new ConcurrentHashMap<>();

        @Override
        public String record(String playerId, List<RewardItem> failed, RewardContext ctx, Throwable cause) {
            String id = "comp_" + seq.incrementAndGet();
            pending.put(id, failed);
            LOG.error("【未持久化】发奖失败已进补偿队列 playerId={} compensationId={} source={} traceId={} "
                            + "失败明细={} 原因={} —— 本实现重启即丢，B12 必须换成持久化且可人工重放",
                    playerId, id, ctx.source(), ctx.traceId(), failed,
                    cause == null ? "业务校验未通过" : cause.getMessage());
            return id;
        }

        public Map<String, List<RewardItem>> pending() {
            return Map.copyOf(pending);
        }
    }

    /**
     * 非资源非道具奖励：一律抛异常，让发放器把它记进补偿队列。
     *
     * <p>刻意不返回「发放成功」：静默成功是最坏的结果 —— 玩家看到已获得但账户里没有，
     * 而且系统里没有任何痕迹可查。响亮地失败并留下补偿记录，才是可运维的行为。
     */
    public static final class UnsupportedExtras implements RewardPorts.Extras {

        @Override
        public long grant(String playerId, RewardType type, String id, long count) {
            throw new UnsupportedOperationException("奖励类型 " + type + "（id=" + id + "）尚未落地："
                    + switch (type) {
                        case HERO, HERO_FRAGMENT -> "由 B06 武将系统实现";
                        case STAMINA -> "由 B09 PVE 与关卡内容实现";
                        case PRIVILEGE -> "由 B03 队列特权与 B15 月卡实现";
                        case RESOURCE, ITEM -> "不应走到这里：资源与道具由 Wallet / Bag 处理";
                    });
        }
    }
}
