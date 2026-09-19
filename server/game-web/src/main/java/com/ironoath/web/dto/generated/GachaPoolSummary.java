// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个卡池在界面上的那一行：叫什么、是哪种池、抽一次花什么、这个号还能抽几次。**只有名字与消耗，没有概率** —— 概率必须走 `/gacha/probability` 那份原文公示，两处各写一份就会分叉（B06 §6 的禁止项就是客户端自己写概率）。
 *
 * **为什么要单独有这一份列表**：`/gacha/probability` 与 `/gacha/draw` 都要带 `poolId`，而客户端对「有哪些池」的唯一知情来源是 gacha 表。没有这个读口，抽卡界面只能把池 id 硬编码进代码 —— 那既是抄表，又让「上一个新池」必须发一次客户端版本。
 */
public record GachaPoolSummary(
        String poolId,   // gacha 表的行 id，也就是 `/gacha/probability` 与 `/gacha/draw` 要带的 poolId。
        String name,   // 卡池中文名（gacha 表的 name 列）。#255 建筑名、#268 资源名、#278 技能名、#281 碎片名、#291 合成候选名同一路：玩家看的名字由服务端给，池页签不许自己拼。
        String poolType,   // 池类型原值（NEWBIE / STANDARD / LIMITED，与 gacha 表的 poolType 列同源）。**界面上不印它** —— 那是枚举外泄（#268 同族），本字段只用来决定那一行归哪个分组。
        String costItemId,   // 按道具计价的池子（限定池用宝箱）给道具行 id，否则为 null。与 costResource 恰好一个非空。
        String costItemName,   // `costItemId` 那一行的中文名（服务端查 item 表的 name 列）；按资源计价时为 null。 #255 建筑名、#268 资源名、#278 技能名、#281 碎片名、#291 合成候选名同族第五处：限定池抽一次花的是 `item_chest_hero`，客户端手里只有这个行 id —— 不随行下发就只能把它印给玩家。
        long costCount,   // **单抽**消耗数（十抽是它乘 10）。余额够不够抽这一档必须拿它比 —— 客户端写死 150 / 1200 的后果是表一改就出现「明明够钱却说不够」。
        long lifetimeLimit,   // 该号在该池的终身抽取上限，0 表示不限。与 `lifetimeDraws` 一起才能算出"还能抽 N 次"，两者都必须来自服务端 —— 少一个就会出现"点得动但注定被拒"的那一种按钮。
        long lifetimeDraws,   // 该号在该池**已经抽过**几次，服务端从池状态里读（与 `draw` 那句超限拒绝同一个数）。 **为什么必须服务端给**：上限（`lifetimeLimit`）在表里客户端读得到，但"还剩几次"要减去已抽次数，而那个数只存在于服务端的池状态里。客户端猜不到的后果是实的：新手池上限是 1，抽满过的玩家仍会看到一个照常能点的页签，点下去吃一条 RATE_LIMITED —— 界面把"已经用掉了"这件事表达不出来，玩家只会以为抽卡坏了。
        String costResource)   // 按资源计价的池子（新手池 / 标准池用 GOLD）给资源枚举原值，否则为 null。**不许把这个枚举原文印给玩家**（#268 那条），中文名走 `game/ui/ResourceNames` 那一份真源。
{
}
