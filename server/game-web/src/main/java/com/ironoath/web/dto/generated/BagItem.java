// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 背包中一个道具条目（B04 §3）。sortKey 由服务端算好下发，客户端不再自行排序——排序规则（稀有度>类型>数量）属于业务逻辑，放客户端会导致双端排序不一致。
 */
public record BagItem(
        String itemId,   // item 表的行 id
        String name,   // 中文名，来自配置表，客户端不得自行翻译
        String type,   // 道具类型，用于分页：SPEEDUP / RESOURCE / CHEST / MATERIAL / BUFF
        ItemRarity rarity,
        String obtainFrom,   // 来源提示（B04 §3：长按显示「来自：第七章宝箱」，让玩家知道去哪再刷）。来自 item 表，未配置时为空串。
        long count,
        long stackMax,
        boolean sellable,
        Long sellPriceGold,
        long sortKey)   // 服务端算好的排序键（稀有度>类型>数量），客户端按它升序展示即可
{
}
