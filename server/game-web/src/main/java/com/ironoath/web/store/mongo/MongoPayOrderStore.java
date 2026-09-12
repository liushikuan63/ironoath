package com.ironoath.web.store.mongo;

import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.pay.PayOrderStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.ArrayList;
import java.util.List;

/**
 * 职责：支付订单的 MongoDB 实现（生产存储）。
 * 依赖：Spring Data MongoDB、{@link PayOrderDocument}。
 *
 * <p><b>这条实现补的是 #16 里后果最重的一个洞</b>：内存登记簿重启即丢，而丢掉的里面
 * 恰恰可能有 {@code PAID_UNFULFILLED} —— 钱已经收了、货还没发的那笔负债。
 * 丢了它，玩家侧是"付了钱什么都没拿到"，而系统里没有任何一处记得这件事（补单队列也查不到）。
 *
 * <p>{@link #save} 与内存版同一条口径：<b>只更新已存在的订单</b>，落空就抛。
 * 静默插入会让 {@link #insert} 的唯一性检查变成摆设，而 orderId 是回调幂等的唯一键 ——
 * "两笔支付共用一个幂等键"就是重复发货。
 */
public final class MongoPayOrderStore implements PayOrderStore {

    private final MongoTemplate mongo;

    public MongoPayOrderStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public PayOrder get(String orderId) {
        if (orderId == null) {
            return null;
        }
        PayOrderDocument doc = mongo.findById(orderId, PayOrderDocument.class,
                PayOrderDocument.COLLECTION);
        return doc == null ? null : doc.toDomain();
    }

    @Override
    public void insert(PayOrder order) {
        if (order == null) {
            throw new IllegalArgumentException("订单不得为 null");
        }
        try {
            mongo.insert(PayOrderDocument.fromDomain(order), PayOrderDocument.COLLECTION);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("订单号重复：" + order.orderId()
                    + "。orderId 是回调幂等的唯一键，重复就等于两笔支付共用一个幂等键", e);
        }
    }

    @Override
    public void save(PayOrder order) {
        if (order == null) {
            throw new IllegalArgumentException("待保存的订单不得为 null");
        }
        PayOrderDocument doc = PayOrderDocument.fromDomain(order);
        Query query = Query.query(Criteria.where("_id").is(order.orderId()));
        Update update = new Update()
                .set("playerId", doc.playerId())
                .set("totalCents", doc.totalCents())
                .set("state", doc.state());
        var result = mongo.updateFirst(query, update, PayOrderDocument.class,
                PayOrderDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            throw new IllegalStateException("订单不存在，无法更新：orderId=" + order.orderId()
                    + "。先 insert 再 save —— 静默插进去会让重复幂等键绕过唯一性检查");
        }
    }

    @Override
    public List<PayOrder> retryQueue(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须 >= 1，实际=" + limit);
        }
        // 只捞负债那一小撮：已付款未发货。补单队列在渠道故障时会瞬间涨到几千条，
        // 所以必须带 limit（分页），与 B14 结算同一条纪律
        List<PayOrderDocument> docs = mongo.find(
                Query.query(Criteria.where("state.status").is(PayOrder.Status.PAID_UNFULFILLED.name()))
                        .limit(limit),
                PayOrderDocument.class, PayOrderDocument.COLLECTION);
        List<PayOrder> out = new ArrayList<>(docs.size());
        docs.forEach(d -> out.add(d.toDomain()));
        return out;
    }

    @Override
    public long unfulfilledCents() {
        // 这里刻意不做"只取前 N 条"：对账要的是全额，少算就是账面不平。
        // 它必须最终归零，所以正常运营下这条查询的命中集很小；真变大就是负债积压，该报警而不是该优化
        List<PayOrderDocument> docs = mongo.find(
                Query.query(Criteria.where("state.status").is(PayOrder.Status.PAID_UNFULFILLED.name())),
                PayOrderDocument.class, PayOrderDocument.COLLECTION);
        long total = 0L;
        for (PayOrderDocument doc : docs) {
            total += doc.totalCents();
        }
        return total;
    }

    /**
     * 月度限额用的"本月已花"。要算哪些状态由 {@link PayOrder#CONFIRMED_STATUS_NAMES} 给 ——
     * 与领域侧 {@code paymentConfirmed()} 是同一份定义，不在这里抄第二个清单。
     */
    @Override
    public long paidCentsSince(String playerId, long sinceMillis) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        List<PayOrderDocument> docs = mongo.find(
                Query.query(Criteria.where("playerId").is(playerId)
                        .and("state.status").in(PayOrder.CONFIRMED_STATUS_NAMES)
                        .and("state.paidAt").gte(sinceMillis)),
                PayOrderDocument.class, PayOrderDocument.COLLECTION);
        long total = 0L;
        for (PayOrderDocument doc : docs) {
            total += doc.totalCents();
        }
        return total;
    }

    @Override
    public int expireUnpaid(long ttlMillis, long now) {
        if (ttlMillis <= 0L) {
            return 0;
        }
        long cutoff = now - ttlMillis;
        // 条件更新而不是"读出来改完再写回"：一次请求里可能有几千单要扫，
        // 逐单读写会把下单路径拖成瓶颈。也只可能伤到 PENDING —— 已确认收款的单不是僵尸记录，是负债
        var result = mongo.updateMulti(
                Query.query(Criteria.where("state.status").is(PayOrder.Status.PENDING.name())
                        .and("state.createdAt").lte(cutoff)),
                new Update()
                        .set("state.status", PayOrder.Status.CANCELLED.name())
                        .set("state.failureReason", "超过 PAY_ORDER_TTL_HOURS 未支付，订单作废"),
                PayOrderDocument.class, PayOrderDocument.COLLECTION);
        return (int) result.getModifiedCount();
    }

    /** 测试与运维用：整表清空（内存版 clear() 的对应物）。 */
    public void deleteAll() {
        mongo.remove(new Query(), PayOrderDocument.COLLECTION);
    }

    /** 观测用：当前订单数。 */
    public long count() {
        return mongo.count(new Query(), PayOrderDocument.class, PayOrderDocument.COLLECTION);
    }
}
