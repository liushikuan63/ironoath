package com.ironoath.web.reward;

import com.ironoath.common.time.TimeService;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardPorts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 职责：把发放器的失败记进<b>可查回的</b>补偿台账（{@link RewardCompensationStore}）。
 * 依赖：{@link RewardPorts.Compensation}、存储端口、{@link TimeService}。
 *
 * <p><b>它换掉的是 {@code TransientRewardPorts.TransientCompensation}</b>：那一份把记录放在
 * 进程内的 {@code ConcurrentHashMap} 里，并且 id 是 <b>实例内自增序号</b>（{@code comp_1}、{@code comp_2}…）。
 * 序号在两个后果上都是错的：⑴ 重启后序号从 1 重来，而 Mongo 侧那条 {@code comp_1} 还在 ——
 * 新实例的第一笔欠账会被当成"重复写入"静默丢掉；⑵ 多实例同时记的第一笔共用同一个号。
 * 所以这里换成与 {@code march_} / {@code battle_} / {@code msg_} 同一套随机约定。
 *
 * <p><b>日志照旧保留</b>：台账解决"事后查得到"，ERROR 日志解决"当下就有人被叫醒"。
 * 两者不是替代关系，缺一条的另一条也不该被删 —— 明细写全（source、traceId、每一项 type:id×count）
 * 是为了让值班在人还没到之前就能判断这一笔要不要立刻补。
 */
public final class StoreCompensation implements RewardPorts.Compensation {

    private static final Logger LOG = LoggerFactory.getLogger(StoreCompensation.class);

    private final RewardCompensationStore store;
    private final TimeService timeService;

    public StoreCompensation(RewardCompensationStore store, TimeService timeService) {
        this.store = store;
        this.timeService = timeService;
    }

    @Override
    public String record(String playerId, List<RewardItem> failed, RewardContext ctx, Throwable cause) {
        long now = timeService.serverNow();
        String compensationId = newId();
        String reason = cause == null ? "业务校验未通过" : String.valueOf(cause.getMessage());
        store.save(new RewardCompensationStore.Entry(compensationId, playerId, failed,
                ctx.source(), ctx.sourceRef(), ctx.traceId(), reason, now, null, null, null));
        // 明细逐项打出来：一条「有 3 项没发出去」的日志无法用于补发，而运维手里只有这一条日志
        // 和台账里那一份明细，两者都得写全才谈得上按原样补。
        LOG.error("发奖失败已记入补偿台账 playerId={} compensationId={} source={} sourceRef={} traceId={} "
                        + "待补偿={} 原因={}",
                playerId, compensationId, ctx.source(), ctx.sourceRef(), ctx.traceId(),
                describe(failed), reason, cause);
        return compensationId;
    }

    /** 与 {@code StoreMailbox} 同一套 id 约定：前缀负责可读，随机部分负责跨实例、跨重启不撞号。 */
    private static String newId() {
        return "comp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static String describe(List<RewardItem> failed) {
        if (failed == null || failed.isEmpty()) {
            return "无明细";
        }
        StringBuilder sb = new StringBuilder();
        for (RewardItem item : failed) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(item.type()).append(':').append(item.id()).append('×').append(item.count());
        }
        return sb.toString();
    }
}
