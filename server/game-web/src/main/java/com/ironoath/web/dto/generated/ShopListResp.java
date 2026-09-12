// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /shop/list 响应：某个货币页签的货架 + 本人余额。
 */
public record ShopListResp(
        ShopCurrency currency,   // 回显请求的页签。
        boolean open,   // 这一页现在能不能兑换。`false` 时 `rows` 仍然会给（让玩家看见有什么、以后能换什么），但每一行的 `purchasable` 都是 false。见本文件 description 第 3 条：「还没有」与「不存在」必须是两句话。
        String notice,   // 为什么没开（`open=false` 时给人看的一句话）。
        List<ShopRowView> rows,   // 该货币的全部商品行，按表里的顺序。**不按等级过滤**：等级不够的商品应当显示成「主城 5 级解锁」而不是消失 —— 让玩家知道有这个东西，是解锁类门槛存在的意义。
        Long balance,   // 本人这种货币的当前余额。金币取**惰性结算之后**的值（金币会自然增长，取一个未结算的旧值会让客户端显示「差 3 个买不起」而服务端其实能扣）。 **为 null 表示「商店没有接这种货币的账本」**（今天只有 SEASON_COIN：赛季币的用途口径尚未裁决，见收口清单 #6b），客户端此时不得显示余额 —— 显示一个假的 0 会让玩家以为「我有 0 个赛季币」，而真实情况是「我们还没决定赛季币能干什么」。 标 int64 的理由：服务端的资源与社交货币全是 long，不写 format 生成器会产出 int，而 long→int 的收窄编译期不报错、只在大额时静默绕成负数 —— 客户端显示一个负余额，玩家以为存档坏了。
        long serverNow)   // 服务端时间戳（铁律 5）。客户端据此推算「还有多久到下一个日切/周切」，不得用自己本地时间算 —— 那会让限购显示随玩家改系统时钟而变化。
{
}
