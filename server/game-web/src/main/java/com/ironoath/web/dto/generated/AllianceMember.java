// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 联盟成员的一条摘要。比小队成员多了职位与贡献值 —— 联盟是 30~150 人的组织，「谁在这个组织里出了多少力」必须可见，否则盟主无从判断该提拔谁、该踢谁。
 */
public record AllianceMember(
        String id,   // 玩家 id
        String name,   // 昵称
        long power,   // 展示战力
        AllianceRole role,   // 职位
        long contribution,   // 累计贡献值
        long lastActiveAt,   // 最近活跃的服务端时间戳
        String squadId)   // 该成员所属的小队 id（联盟内分队）；无小队为 null。**必须下发**：盟主集结时要能按分队点名，否则 150 人的名单就是一堆散沙
{
}
