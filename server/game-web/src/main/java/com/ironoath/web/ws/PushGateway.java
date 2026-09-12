package com.ironoath.web.ws;

import java.util.Collection;

/**
 * 职责：服务端主动推送的抽象端口 —— 后续批次（建造完成、行军到达、联盟聊天、被攻击告警）都依赖它。
 * 依赖：无（纯接口）。
 *
 * <p>为什么要抽象：推送的具体实现会随部署形态变化（单实例用内存 session 表，
 * 多实例要换成 Redis Pub/Sub 广播到持有该连接的实例）。玩法层只应该依赖本接口，
 * 否则 B07 行军到达推送会直接把 WebSocketSession 耦合进业务代码，换成多实例时全得改。
 *
 * <p>B01 只提供内存单实例实现 {@link GameWebSocketHandler}；多实例广播在 B16 上线批次处理。
 */
public interface PushGateway {

    /**
     * 向指定玩家推送一条消息。
     *
     * @param playerId 目标玩家
     * @param type     消息类型，客户端按它分发（如 {@code march_arrived}、{@code city_attacked}）
     * @param payload  业务负载，会被序列化成 JSON 的 {@code data} 字段
     * @return true 表示已写入连接；false 表示该玩家不在线（调用方应改为落库，等下次登录补拉）
     */
    boolean pushToPlayer(String playerId, String type, Object payload);

    /** 当前在线连接数，用于监控与埋点（B16）。 */
    int onlineCount();

    /**
     * 此刻在线的全部玩家 id。给「全服广播」用（B08 §4 的公敌档）—— 它的收件人是所有人，
     * 而不是某个能被点名的人，所以没法用 {@link #pushToPlayer} 凑出来。
     *
     * <p>多实例部署时这一份要由聚合层给出（在线表在 Redis 里），单实例的连接表只覆盖本进程。
     * 与本接口整体是同一条待办，见类注释。
     */
    Collection<String> onlinePlayerIds();

    /** 某个玩家是否在线。 */
    boolean isOnline(String playerId);
}
