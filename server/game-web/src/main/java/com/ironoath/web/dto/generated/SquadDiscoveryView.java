// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 可加入小队列表的一行（B26 S7）。与联盟那一行同一条纪律：**满不满由服务端算**，客户端不拿 memberCount 与 memberCap 自己比 —— 小队上限的第二档挂在**队长主城等级**上，那份读数只有服务端拿得到。
 */
public record SquadDiscoveryView(
        String id,   // 小队 id，加入时原样带回
        String name,   // 小队名（建队时填的那个）
        int level,   // 小队等级
        int memberCount,   // 当前人数
        int memberCap,   // 当前人数上限（按队长主城等级算出的生效值，不是等级表第一档）
        boolean full)   // 是否已满：与 Squad.join 会拒绝的条件同一次计算，界面据此把按钮灰掉
{
}
