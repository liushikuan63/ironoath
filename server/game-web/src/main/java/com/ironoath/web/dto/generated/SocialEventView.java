// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条社交事件。推送与离线补偿共用同一结构（B10 验收 5 / 12）。**必须带 occurredAt**：离线补偿时玩家一次收到几十条，没有时间就无法判断哪条还值得响应 —— 三小时前的「盟友被攻击」已经支援不上了，点进去只会看到一片废墟。
 */
public record SocialEventView(
        String eventId,   // 事件 id
        SocialEventType type,   // 事件类型
        String title,   // 标题。服务端拼好下发（如「盟友 张三 正在被攻击」）
        String body,   // 正文，可空
        SocialCoord coord,   // 相关坐标（被攻击地点、集结目标）；与坐标无关的事件为 null。用一个可空的坐标对象而不是 coordX/coordY 两个独立可空整数 —— 后者允许「X 有值而 Y 为 null」这种无意义的组合，而一个整体为 null 的坐标不可能自相矛盾
        String relatedId,   // 相关的组织或集结 id；无则为 null
        long occurredAt,   // 发生时刻（服务端时间戳）
        boolean expired)   // 是否已过期（响应窗口已过）。true 时 UI 必须置灰且不可跳转 —— 与 B07 侦查情报的同一条纪律：过期情报置灰，否则玩家会拿一个已经失效的目标去做决策
{
}
