// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /squad/list 响应体（B26 S7）：可加入小队的前 limit 个。存在的理由与 /alliance/list 同一条 —— 加入是**直接进、不需要审核**的，但玩家连「世界上有哪些小队」都读不到，就只能自己建一支。响应必须有界，所以按等级、人数降序取前 N 个，并把 total 一起下发。
 */
public record SquadListResp(
        List<SquadDiscoveryView> squads,   // 行，按等级降序、同级按人数降序；已解散的小队不在内
        int total,   // 服务器上共有多少个未解散小队（不是本页条数）
        int limit,   // 本次实际生效的条数上限（global.SQUAD_LIST_LIMIT）
        long serverNow)   // 服务端时间戳
{
}
