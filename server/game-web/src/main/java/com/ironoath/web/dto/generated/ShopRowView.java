// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 货架上的一行。**客户端只显示这里给出的内容，绝不自己拼货架**：`requireMainLevel` 与限购在服务端算，客户端拼出来的版本会在下一次热更表之后立刻变成一份过期货架。
 */
public record ShopRowView(
        String rowId,   // `shop` 表的行 id（如 `shop_speedup_build_1h`）。**下单用它是唯一的**：用 itemId 定位会命中同一件道具的多行价格，而那正是「一条商品行一个价格」要防的形状。
        String itemId,   // 买到的道具 id（`item` 表的行 id）。与 rowId 是两个东西，所以两个字段都在。
        String name,   // 表里的显示名。**下发它而不是让客户端内置**：改个名字不该发一次版。
        ShopCurrency currency,   // 这一行花哪种货币。客户端应当据此决定显示哪个余额（金币在资源条上、贡献值在联盟面板里、小队币在小队面板里）。
        long price,   // 单价（货币单位，不是「分」—— 金币/贡献值/小队币都是游戏内计数，只有真实支付金额才用分）。**总花费 = price × count，由服务端算**。 标 int64 的理由与 balance 同一条：它是表里的 LONG 列，而服务端要拿它做乘法 —— 用 int 承接一个 long 列，等于把「以后有人把价格配得很大」变成一次静默溢出而不是一个类型错误。
        ShopRefresh refreshType,   // 限购刷新口径。
        int limitCount,   // 一个周期内最多买几个。`refreshType=NONE` 时它是**永久**上限（表里 LONG_POS 不允许 0，所以「不限购」只能表达成 NONE + 一个足够大的数，这一条由 `why` 说明而不是靠 0）。
        int used,   // 本周期已买几个。给客户端画进度条用 —— 只给 remaining 不够，玩家看到「还能买 3 个」时不知道自己是快用完了还是刚用完一半。
        int remaining,   // 本周期还能买几个 = max(0, limitCount - used)。**由服务端算**：客户端自己减会在跨期的那一刻算错（日切时刻与服务器的 UTC+8 口径可能差好几个小时）。
        int requireMainLevel,   // 解锁所需主城等级，0 表示不限。
        boolean purchasable,   // 此刻这个玩家能不能买。**它是「不满足就为 false」的汇总**：等级不够、次数用完、余额不足、不在这个页签所要求的联盟/小队里 —— 四种都算 false。 之所以要让服务端算这个布尔而不是让客户端自己比：客户端能比的只有它看得见的三个数，而「他今天到底买过几次」「他在不在联盟里」是服务端状态。让客户端猜的结果是按钮亮着、点下去报错。
        String lockReason)   // 不能买的原因（人话），可买时为 null。**必须带原因而不是只有一个灰按钮**：「主城 5 级解锁」与「本周限购已用完」是两种完全不同的玩家动作（继续升等 vs 等下周），而一个没有文案的灰按钮会让玩家以为坏了。
{
}
