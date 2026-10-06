// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /nation/relations 的响应：本国对全部已知国家的关系表（只读）。
 *
 * **为什么单开一个只读口**：`NationDiplomacyResp` 那张表只在「改关系」那一次写入时回，于是**任何别的写入改变了关系之后，客户端手里那张就旧了**——最典型的是宣战（`/nation/war/declare` 会把对目标国那一行置成 HOSTILE），宣完战切到外交页，玩家看到的还是旧关系，读起来就是「宣战了却没敌对」。客户端没有别的地方能把这张表重新读一遍。
 *
 * **为什么回整张表而不是只回变更的那一条**：与 `NationDiplomacyResp` 同一条理由——外交面板本来就要显示全部关系，只回一条会逼客户端自己拼表，那是「客户端拼榜」那一族的开端。
 *
 * **权限**：无（只看得到本国与谁是什么关系；这不是情报，玩家打开外交页本来就该看到）。不在任何国家里时回 13000 `NATION_NOT_FOUND`，与 `/nation`、`/nation/treasury` 同一条。
 */
public record NationRelationsResp(
        List<NationRelationView> allRelations,   // 本国与全部已知国家的关系。亡国的不列出（那份关系仍在档里供审计，但列出来就是给玩家一个点了必然失败的入口）。
        long serverNow)   // 服务端时间戳。
{
}
