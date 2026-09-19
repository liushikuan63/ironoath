// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /alliance/list 响应体（B26 S6）：可申请联盟的**前 limit 个**。这一版不做翻页 —— 联盟是玩家花金币建的、没有机器人批量造，总量天然小；但响应必须有界，所以按等级、人数降序取前 N 个，并把 total 一起下发，界面写「共 X 个，只显示前 Y 个」。
 */
public record AllianceListResp(
        List<AllianceDiscoveryView> alliances,   // 行，按等级降序、同级按人数降序
        int total,   // 服务器上共有多少个联盟（不是本页条数）
        int limit,   // 本次实际生效的条数上限（global.ALLIANCE_LIST_LIMIT）
        long serverNow)   // 服务端时间戳
{
}
