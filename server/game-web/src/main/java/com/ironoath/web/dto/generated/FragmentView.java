// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一档稀有度的碎片余额，**带中文名**。
 *
 * **为什么不复用 ItemCount**（原先这里写的正是「走 ItemCount 而不是新造一个类型」）：
 * 武将页那一行要把碎片展示给玩家，而 `ItemCount` 只有 `itemId` ⇒ 画面上出来的是
 * `item_mat_hero_frag_sr ×12` 这种行 id（#255 建筑名、#268 资源名、#278 技能名之后的同族第四处）。
 * **也不许客户端拿背包去 join**：本响应的碎片行**包含余数为 0 的档**（某一档没攒过也要让玩家看到 0），
 * 而背包只列余数大于 0 的行 —— join 的结果是「越没有越看不见名字」，症状正是退回印 id。
 */
public record FragmentView(
        String itemId,   // item 表的行 id（item_mat_hero_frag_*）
        String name,   // 道具中文名，服务端按 itemId 查 item 表的 name 列（与 RewardNames 对碎片的取法同源）
        long count)
{
}
