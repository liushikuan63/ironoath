package com.ironoath.web.store.mongo;

import com.ironoath.web.ops.TrackEventStore;
import org.springframework.data.annotation.Id;

import java.util.UUID;

/**
 * 职责：一条已落库埋点事件的 MongoDB 文档。
 * 依赖：{@link TrackEventStore.TrackRecord}（事件本体就是它，不摊平）。
 *
 * <p><b>{@code _id} 为什么是存储侧造的随机键</b>：埋点事件<b>没有自然键</b> ——
 * 同一毫秒可以有两条同名同玩家的事件（一次战斗的多个回合就是这样），
 * 用 {@code name+serverTs} 之类拼出来的"键"会在不该去重的时候去重，
 * 表现是漏斗里少了几环而没人报错。随机键意味着<b>重复投递被记成两条事实而不是互相覆盖</b>，
 * 与内存版"addLast 两次就是两条"同一条语义。
 *
 * <p>{@code playerId} 与 {@code serverTs} 提到文档级只为索引（"某玩家最近 N 条"与按保留期清理），
 * 判定与展示读的都还是 {@code event} 里那一份。玩家 id 可空（登录前的事件没有身份），
 * 空值照样落这一列，看板侧按 null 分组才看得见"进都没进就走了"那一段漏斗。
 */
public record TrackEventDocument(
        @Id String id,
        String playerId,
        long serverTs,
        TrackEventStore.TrackRecord event) {

    /** 集合名。 */
    public static final String COLLECTION = "track_event";

    static TrackEventDocument of(TrackEventStore.TrackRecord event) {
        return new TrackEventDocument(UUID.randomUUID().toString(), event.playerId(),
                event.serverTs(), event);
    }
}
