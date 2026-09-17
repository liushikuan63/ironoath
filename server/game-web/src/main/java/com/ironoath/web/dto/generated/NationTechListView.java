// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * `GET /nation/tech` 的响应。四行全量下发（不分页）：行数由表决定，客户端不该知道有几行。
 * **玩家没有国家时不走这个视图**，直接是既有拒绝 `NATION_NOT_FOUND`（与 `/nation` 同一枚码、同一句理由），
 * 所以这里的 `nationId` 与 `treasury` 恒非空 —— 不声明"可选的国家"这种要让客户端猜的形状。
 */
public record NationTechListView(
        String nationId,
        String nationName,
        int nationLevel,   // 国家当前等级（`Nation.level()`）。所有 `requireNationLevel` 比较的唯一数字来源。
        long treasury,   // 国库**已结算税收之后**的余额。花费判定读的就是这一位。
        List<NationTechView> techs,   // 四行（顺序 = 表序，服务端不重排）。
        long serverNow)   // 服务端时刻（毫秒）。国库支出的**周限额**按这一位算周，客户端不许自己算（那是第二个家）。
{
}
