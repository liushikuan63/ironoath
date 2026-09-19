package com.ironoath.web.reward;

import com.ironoath.core.reward.RewardItem;

import java.util.List;
import java.util.Optional;

/**
 * 职责：发奖失败的<b>补偿台账</b>端口（B04 验收 7「grantReward 异常时不静默：落日志 + 补偿队列有记录」）。
 * 依赖：game-core 的 {@link RewardItem}（纯数据）。
 *
 * <p><b>为什么这条记录必须活得比进程长</b>：写进这里的每一条都是「玩家该得、但当下没拿到」的东西。
 * 它此前的实现只在内存里（{@code TransientCompensation}），于是重启把唯一的事实抹掉，
 * 剩下的只有一行历史日志 —— B04 禁止项「不要在发奖失败时静默吞掉异常」的<b>后半句</b>
 * （补偿队列要有记录）在重启之后就不成立了：有记录 ≠ 记录会消失。
 * 玩家投诉「我领了的三件套没到账」时，无据可查就是无法处理。
 *
 * <p><b>端口放在 game-web 而不是 game-core</b>：与 {@code MailStore} 同一条理由 ——
 * game-core 里没有任何逻辑要读这张台账（发放器只<b>写</b>，读与处理全在运维侧的 web）。
 *
 * <p><b>为什么这里不自动重试</b>：一次发放失败的原因大多是「这个玩家的子系统此刻放不下 / 装不下」，
 * 而不是「再试一次就好」。盲目重投会把一次失败变成 N 次重复发放（那是经济口子，不是显示问题）。
 * 出口因此只有人工一条：运维看着这份台账，用已经审计过的 {@code POST /ops/mail/send}
 * 按原明细补发一封邮件，再回到这里把该条标成已处理并写上那封邮件的 id。
 * <b>补发的那条通路本身已经带幂等键与审计</b>，重试通路再写一套就是两处真相。
 *
 * <p>内存版供 dev/test，Mongo 版供生产；两者语义逐条一致，由
 * {@code RewardCompensationStoreEquivalenceTest} 比对返回值与异常文本钉住。
 */
public interface RewardCompensationStore {

    /**
     * 一条欠账。
     *
     * @param compensationId 台账主键，形如 {@code comp_xxxx}，回执给玩家侧日志与客服工单
     * @param playerId       欠谁
     * @param failed         没发放成功的明细。<b>空列表不合法</b>：一条「欠 0 件东西」的补偿记录
     *                       只会出现在把 granted 误当 failed 传进来的调用点，那种错误必须当场响
     * @param source         来源系统（quest / activity / battle / mail / shop / …），风控按它归因
     * @param sourceRef      来源引用（任务 id、战报 id、订单 id…），没有时是空串
     * @param traceId        链路 id，与当时那次 HTTP 响应的 traceId 一致（铁律 10）
     * @param reason         失败原因摘要，可为空串（业务校验不通过时没有异常对象）
     * @param createdAt      记账时刻（服务端时钟）
     * @param resolvedAt     处理完成的时刻，{@code null} 表示还没处理
     * @param resolvedBy     处理人（运维令牌的操作者标识），未处理时为 null
     * @param resolution     处理说明。<b>兑付通路是运营补发邮件</b>，所以这里期望写的是那封邮件的 id
     */
    record Entry(String compensationId, String playerId, List<RewardItem> failed, String source,
                 String sourceRef, String traceId, String reason, long createdAt,
                 Long resolvedAt, String resolvedBy, String resolution) {

        public Entry {
            require(compensationId, "compensationId");
            require(playerId, "playerId");
            require(source, "source");
            require(traceId, "traceId");
            if (failed == null || failed.isEmpty()) {
                throw new IllegalArgumentException("补偿记录必须带上没发出去的明细：一条欠 0 件的记录"
                        + "只会来自「把已发放当成待补偿」的调用点");
            }
            failed = List.copyOf(failed);
            sourceRef = sourceRef == null ? "" : sourceRef;
            reason = reason == null ? "" : reason;
            if (resolvedAt != null && (resolvedBy == null || resolvedBy.isBlank())) {
                throw new IllegalArgumentException("已处理的补偿记录必须写明处理人："
                        + "「谁把这笔债销掉的」与「谁欠的」同样重要，否则台账变成可以悄悄抹平的地方");
            }
        }

        /** 还没处理。 */
        public boolean pending() {
            return resolvedAt == null;
        }

        private static void require(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("compensation." + field
                        + " 不得为空：没有" + field + "的补偿记录既无法归因也无法兑付");
            }
        }
    }

    /**
     * 记一笔欠账。同一 {@code compensationId} 重复写入不覆盖已有记录（幂等，与邮件端口同一约定）——
     * 已经被人处理过的那一条被一次重投的 ERROR 路径盖回未处理，是台账最不该发生的事。
     */
    void save(Entry entry);

    /** 未处理的欠账，<b>最旧的在前</b>（运维按先到期先处理的顺序清账）。最多 {@code limit} 条。 */
    List<Entry> pending(int limit);

    /** 未处理的总笔数，不受 limit 影响 —— 「只列了 20 条」不能被读成「一共只有 20 笔」。 */
    int countPending();

    Optional<Entry> findById(String compensationId);

    /**
     * 把一笔欠账标成已处理。<b>只有从「未处理」翻到「已处理」的那一次返回 true</b>。
     *
     * <p>为什么必须原子：两个人同时处理同一条（客服和值班各补发了一封邮件）时，
     * 后一次若也算成功，台账上就只留得下一个人的说明，而前一个人的补发邮件变成无主记录。
     *
     * @return true 表示这一次赢得处理权；false 表示它已被处理、或压根不存在
     */
    boolean resolve(String compensationId, String resolvedBy, String resolution, long nowMillis);

    /** 存量总条数（含已处理，健康度与测试用）。 */
    int count();

    /** 测试辅助：清空。 */
    void clear();
}
