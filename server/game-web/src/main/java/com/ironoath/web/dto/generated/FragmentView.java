// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一档稀有度的碎片余额，**带中文名**。
 *
 * **为什么不复用 ItemCount**（原先这里写的正是「走 ItemCount 而不是新造一个类型」）：
 * 武将页那一行要把碎片展示给玩家，而 `ItemCount` 只有 `itemId` ⇒ 画面上出来的是
 * `item_mat_hero_frag_sr ×12` 这种行 id（#255 建筑名、#268 资源名、#278 技能名之后的同族第四处）。
 * **也不许客户端拿背包去 join**：本响应的碎片行**包含余数为 0 的档**（某一档没攒过也要让玩家看到 0），
 * 而背包只列余数大于 0 的行 —— join 的结果是「越没有越看不见名字」，症状正是退回印 id。
 *
 * **门槛与候选同批下发**（V03-d 第六条养成线）：只有余额的话界面只能说「你有 12 片」，
 * 说不出「还差 38 片就能合成典韦」—— 而后一句才是玩家按下按钮的理由。
 */
public record FragmentView(
        String itemId,   // item 表的行 id（item_mat_hero_frag_*）
        String name,   // 道具中文名，服务端按 itemId 查 item 表的 name 列（与 RewardNames 对碎片的取法同源）
        long count,
        long composeFragment,   // 合成这一档武将要消耗的碎片数，服务端按该稀有度查 hero_rarity 表的 composeFragment 列。 **为什么由服务端下发**：客户端一旦抄了 hero_rarity.json 就有了第二份真相，表改了「还差几片」会照旧说够。 算这句需要两个数 —— 门槛与余额，两个都必须来自本响应。
        List<ComposeCandidate> candidates)   // 这一档里**尚未拥有**、可用碎片合成的武将，顺序照 hero 表的行序。空数组＝没有可合成的 （该档武将全已拥有，或这一档根本没有武将）。 **为什么必须服务端给**：玩家没有的武将从不进 heroes 数组，客户端对「这一档有哪些武将」的唯一知情来源 就是 hero 表 —— 那正是不能抄的东西。已拥有的在服务端就排除掉，因为 `/hero/compose` 对已拥有直接拒绝 （不排除了会让玩家点一行注定被拒的武将）。
{
}
