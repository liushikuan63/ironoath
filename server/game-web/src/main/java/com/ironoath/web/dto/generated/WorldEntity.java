// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 地图实体的精简结构 —— 控制单次 payload ≤ 20KB（B07 验收 5）。<b>刻意不包含 isBot</b>：B07 禁止项与 B11 合规都明写「前 7 天不做任何 Bot 标识」，一旦这个字段进了协议，客户端就可能拿它做差异化展示，而监管会认定为「人机混排未告知」。字段不存在比字段值为 false 更安全 —— 不存在的字段无法被误用。
 */
public record WorldEntity(
        String id,   // 实体 id：玩家城 = playerId，野怪 = 坐标派生的稳定 id，资源点同
        WorldEntityType type,
        int x,
        int y,
        Integer level,   // 野怪等级 / 主城等级；空地与资源点为 null
        String ownerName,   // 玩家城的拥有者昵称；野怪与资源点为 null
        String allianceTag,   // 联盟标签（B10 落地前恒为 null，字段先留位以免届时改协议）
        MarchStatus marchStatus,
        String resourceType,   // 资源点产出的资源 id；其余为 null
        Long load)   // 行军实体的当前负载
{
}
