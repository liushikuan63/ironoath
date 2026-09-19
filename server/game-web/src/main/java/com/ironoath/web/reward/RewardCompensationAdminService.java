package com.ironoath.web.reward;

import com.ironoath.common.time.TimeService;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.web.dto.generated.CompensationResolveReq;
import com.ironoath.web.dto.generated.CompensationResolveResp;
import com.ironoath.web.dto.generated.CompensationResp;
import com.ironoath.web.dto.generated.CompensationRewardView;
import com.ironoath.web.dto.generated.CompensationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：补偿台账的运维侧读与销账（B04 验收 7 的出口）。
 * 依赖：{@link RewardCompensationStore}、{@link TimeService}。
 *
 * <p><b>为什么单独一个服务而不是塞进 {@code OpsAppService}</b>：OpsAppService 已经带着埋点落库、
 * 崩溃看板、配置热更、客服入口四件事的依赖，再挂一本奖励欠账进去，
 * 任何一处想读"补偿台账"的人都得连带构造那一大串。台账只认存储端口和时钟。
 *
 * <p><b>本类没有任何自动重投</b>，这是设计而不是没做（理由写在 {@link RewardCompensationStore} 的类注释：
 * 重投会把一次失败变成 N 次重复发放）。兑付走已有的 {@code POST /ops/mail/send}，
 * 那条通路自带幂等键、运维令牌与审计日志 —— 再造一条补偿专用通路就是同规则两处表达。
 */
public final class RewardCompensationAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(RewardCompensationAdminService.class);

    /**
     * 明细最多带几条。
     *
     * <p><b>必须夹住</b>：这条路径挂在不需要玩家身份的 {@code /ops/} 前缀下，
     * 把 limit 原样透传给存储层等于任何人（令牌泄露时）都要求服务端把整张台账捞一遍。
     * 与 {@code PayAppService.DEBT_LIST_MAX} 同一个理由。总数单独回（{@code pendingCount}），
     * 所以「没列全」在响应里是看得见的。
     */
    private static final int LIST_MAX = 50;

    private final RewardCompensationStore store;
    private final TimeService timeService;

    public RewardCompensationAdminService(RewardCompensationStore store, TimeService timeService) {
        this.store = store;
        this.timeService = timeService;
    }

    /** 待处理的欠账（只读）。最旧的在前。 */
    public CompensationResp list(int limit) {
        int capped = Math.max(1, Math.min(limit, LIST_MAX));
        List<RewardCompensationStore.Entry> entries = store.pending(capped);
        List<CompensationRow> rows = new ArrayList<>(entries.size());
        for (RewardCompensationStore.Entry entry : entries) {
            rows.add(rowOf(entry));
        }
        return new CompensationResp(store.countPending(), rows.size(), rows);
    }

    /**
     * 销一笔账。<b>只有把「未处理」翻成「已处理」的那一次返回 resolved=true</b>。
     *
     * <p>两种"没改成"要分开说，因为下一步动作不同：
     * <ul>
     *   <li>{@code compensationId} 根本不存在（多半是抄错了号）→ 报参数错误。
     *       把它和"已被别人处理"混成一句 resolved=false，值班会以为自己那封补发邮件白发了，
     *       而真实情况是他挑的那条记录从没进过台账；</li>
     *   <li>存在但已被别人处理 → resolved=false，且响应里的 {@code pendingCount} 仍然如实给，
     *       于是"少了一笔"这件事看得见。</li>
     * </ul>
     * 这里不新增错误码：台账不存在不是一个业务状态，是一次调用错误。
     */
    public CompensationResolveResp resolve(CompensationResolveReq req) {
        if (req == null || isBlank(req.compensationId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "compensationId 不得为空");
        }
        if (isBlank(req.actor())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "actor 不得为空：谁把这笔债销掉的，与谁欠的同样重要");
        }
        if (isBlank(req.resolution())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "resolution 不得为空：期望写兑付凭证（补发邮件的 mailId）");
        }
        if (store.findById(req.compensationId()).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "补偿记录不存在: " + req.compensationId());
        }
        boolean flipped = store.resolve(req.compensationId(), req.actor().trim(),
                req.resolution().trim(), timeService.serverNow());
        // 无论成不成都要留痕：销账等于把一笔"我们欠玩家东西"的记录划掉，
        // 而这条端点不需要玩家身份 —— 能被审计到是它唯一的约束
        LOG.warn("补偿台账销账 compensationId={} actor={} 凭证={} 结果={} 剩余待处理={}",
                req.compensationId(), req.actor(), req.resolution(), flipped ? "本次销账成功" : "未翻转（已被处理）",
                store.countPending());
        return new CompensationResolveResp(req.compensationId(), flipped, store.countPending());
    }

    private static CompensationRow rowOf(RewardCompensationStore.Entry entry) {
        List<CompensationRewardView> items = new ArrayList<>(entry.failed().size());
        for (RewardItem item : entry.failed()) {
            items.add(new CompensationRewardView(item.type().name(), item.id(), item.count()));
        }
        return new CompensationRow(entry.compensationId(), entry.playerId(), entry.source(),
                entry.sourceRef(), entry.traceId(), entry.reason(), entry.createdAt(), items);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
