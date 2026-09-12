// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 小队成员的一条摘要（B10 §二）。power 是**展示战力**而不是匹配战力 —— 小队里看的是「兄弟练得怎么样」，不是「我能不能打他」；圈层校验用的匹配战力只在 B08 的搜索与攻击链路里出现。
 */
public record SquadMember(
        String id,   // 玩家 id
        String name,   // 昵称。服务端下发，客户端不得自行翻译或截断
        long power,   // 展示战力
        long lastActiveAt,   // 最近活跃的服务端时间戳。小队只有 5~10 人，谁三天没上线一眼就该看出来 —— 这是队长决定要不要补人的唯一依据
        SquadRole role,   // 职位
        int mainCityLevel)   // 主城等级。**必须下发**：小队人数上限的第二档门槛是「队长主城 8 级」（B10 §1），客户端要能解释「为什么现在只能 5 人」
{
}
