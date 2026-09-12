// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条外交关系（面板用）。
 */
public record NationRelationView(
        String nationId,   // 对方国家 id。
        String nationName,   // 对方国名，服务端下发。客户端不得自行拼接：那份名字要与战报、聊天、客服工单里的称呼一致。
        DiplomacyRelation relation)   // 当前关系。
{
}
