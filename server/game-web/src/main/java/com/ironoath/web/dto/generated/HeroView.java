// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一名已拥有武将的完整状态（五条养成线各占一段）。
 */
public record HeroView(
        String heroId,
        String name,   // 中文名，来自 hero 表
        HeroRarity rarity,
        int level,
        long exp,   // 当前等级内已累计的经验（溢出的经验会连续升级，见 HeroGrowth）
        long expToNext,   // 升到下一级还需多少经验；满级时为 0
        int maxLevel,
        int star,
        int maxStar,
        int awaken,
        int maxAwaken,   // 来自 hero 表 awakenMax，逐武将不同（SSR 3 / SR 2 / R 1）
        String mainSkillId,
        String mainSkillName,   // 主技能的中文名（skill 表的 name 列，服务端按 mainSkillId 查表随视图下发）。 **为什么名字要服务端给**：武将页那一行原先直接印 `skill_guanyu_main Lv3/10` —— 把配置行 id 印给玩家。 这与 #255（建筑显示名）、#268（资源中文名）同一族，裁决早就定了：客户端不许自己拼名字、也不许抄一份第二真源。
        int mainSkillLevel,
        String subSkillId,
        String subSkillName,   // 副技能的中文名，取法与 mainSkillName 完全一致（同一行代码写出来的两个字段，不该有一个是 id）。
        int subSkillLevel,
        int maxSkillLevel,
        List<WornEquip> equips,   // 这个武将身上穿着的装备，**每项自带槽位**（见 WornEquip）。 **为什么不再是定长 4 格**：原先是 `(string|null)[]`，装的是**实例 uid**， 于是武将页那一行只能印 `武器：eq-7f3a…` —— 把内部编号印给玩家（#255/#268/#278/#281 同族第五处）。 要带上名字与强化等级就得让每项是个对象，而 JSON Schema 表达不出「对象或 null」的数组元素 （`type` 数组只能列标量、生成器也没实现 `oneOf`）；把空槽写成「一个名字为空的 WornEquip」 又会把「没穿」和「穿了个没名字的」混成同一个值。所以改成**只列穿着的**：空槽＝数组里没有那一项， 客户端按 `slot` 落到四格里。原描述「定长数组是为了让客户端不必猜键名顺序」这条理由仍然成立 —— 每项自己写着槽位。
        AttrTriple baseAttrs,
        AttrTriple finalAttrs,
        long power,   // 该武将的战力贡献，走 curve.HERO_GROWTH（幂律），仅用于展示与 B08 圈层匹配，不进战斗公式
        String bondWith)   // 缘分对象武将 id；null 表示该武将没有缘分
{
}
