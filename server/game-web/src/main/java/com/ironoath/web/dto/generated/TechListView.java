// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * `GET /tech/list` 的响应。读取有副作用：与 `/city/list` 一样顺带结算到期研究（否则完成时刻已到的研究在玩家眼里还是「进行中」）。
 */
public record TechListView(
        List<TechView> techs,   // 整棵树（11 行，顺序 = 表序）。服务端不重排，客户端不许自己排。
        TechQueueView queue,   // 当前队列。
        int academyLevel,   // 学院建筑当前等级（0 = 还没建）。前置校验的唯一数字来源：读的是城建的真实等级，不是玩家说建了几级。
        long serverNow)   // 服务端时刻（毫秒）。
{
}
