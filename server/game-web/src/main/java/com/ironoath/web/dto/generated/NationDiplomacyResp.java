// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 外交变更的响应：回双方之间**生效后**的关系，以及本国对全部国家的关系表。回整张表而不是只回变更的那一条，是因为客户端的外交面板本来就要显示全部关系，只回一条会逼它再发一次查询。
 */
public record NationDiplomacyResp(
        String targetNationId,   // 对方国家 id。
        DiplomacyRelation relation,   // 生效后的关系。
        List<NationRelationView> allRelations,   // 本国与全部已知国家的关系。
        long serverNow)   // 服务端时间戳。
{
}
