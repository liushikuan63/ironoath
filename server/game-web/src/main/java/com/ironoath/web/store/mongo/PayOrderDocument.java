package com.ironoath.web.store.mongo;

import com.ironoath.core.pay.PayOrder;
import org.springframework.data.annotation.Id;

/**
 * 职责：支付订单的 MongoDB 文档模型。
 * 依赖：{@link PayOrder.Snapshot}。
 *
 * <p>业务字段就是领域快照（理由同 {@link MarchDocument}：抄一份镜像字段表就等于再造一次
 * "什么算完整"，而这条记录漏一个字段的代价是"钱收了、订单没了"）。
 *
 * <p>{@code totalCents} 是冗余列（= {@code state.unitPriceCents × count}）：它不参与任何判定，
 * 只让"未发货负债总额"这种对账查询不必把每份快照都拉回应用层再乘。
 */
public record PayOrderDocument(
        @Id String orderId,
        String playerId,
        long totalCents,
        PayOrder.Snapshot state) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "pay_order";

    static PayOrderDocument fromDomain(PayOrder order) {
        PayOrder.Snapshot state = order.snapshot();
        return new PayOrderDocument(state.orderId(), state.playerId(),
                state.unitPriceCents() * state.count(), state);
    }

    PayOrder toDomain() {
        return PayOrder.fromSnapshot(state);
    }
}
