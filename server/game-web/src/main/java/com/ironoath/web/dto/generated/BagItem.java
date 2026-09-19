// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 背包中一个道具条目（B04 §3）。sortKey 由服务端算好下发，客户端不再自行排序——排序规则（稀有度>类型>数量）属于业务逻辑，放客户端会导致双端排序不一致。
 *
 * **2026-09-13 裁决：`sellable` / `sellPriceGold` 不再下发**（原先这两个字段在这里、客户端据此渲染「可出售」，而服务端没有 /bag/sell 端点、B04 整篇没有出售规则 ⇒ 下发一份「点了只会失败」的数据，与 #18/#19 的「卖了没用」同族）。item 表里那两列**保留**（那是将来定规则的数据），规则与端点落地后再随协议回来。
 */
public record BagItem(
        String itemId,   // item 表的行 id
        String name,   // 中文名，来自配置表，客户端不得自行翻译
        String type,   // 道具类型，用于分页：SPEEDUP / RESOURCE / CHEST / MATERIAL / BUFF
        ItemRarity rarity,
        String obtainFrom,   // 来源提示（B04 §3：长按显示「来自：第七章宝箱」，让玩家知道去哪再刷）。来自 item 表，未配置时为空串。
        long count,
        long stackMax,
        long sortKey,   // 服务端算好的排序键（稀有度>类型>数量），客户端按它升序展示即可
        String effectKind,   // 这道具用下去是干什么的（item 表 effectKind 列的原值，如 GRANT_HERO_EXP / AWAKEN_HERO / UP_HERO_SKILL / COMPOSE_HERO）。 **为什么必须下发**：`type` 太粗 —— 三本经验书是 MATERIAL，而 MATERIAL 底下还有 11 种别的材料。「哪个道具能喂武将」这种问题不该由客户端按 id 硬编码回答（与 #255「名字不许客户端自己拼」同一根因，只是这次缺的是**判别字段**而不是名字）。武将养成的四个选择弹层按这一列筛候选，筛错的下场只是一次被拒（服务端仍各自校验）。
        String effectTarget)   // effectKind 的**宾语**（item 表 effectTarget 列的原值，未配置时不下发）：资源类填 WOOD/STONE/…，技能书填 MAIN/SUB。 **为什么技能弹层非要有它**：`POST /hero/skillUp` 的 `skillSlot` 不许客户端猜 —— `HeroAppService#growByItem` 里那句 `target != null && !target.equals(skillSlot.name())` 会把猜错的拒掉，而「这本是主技能书」这件事只有这一列知道（`type` 与 `effectKind` 两列下两本书完全同型）。有了它，弹层是**书决定槽位**，而不是让玩家选一个槽位再赌一本对得上的书。
{
}
