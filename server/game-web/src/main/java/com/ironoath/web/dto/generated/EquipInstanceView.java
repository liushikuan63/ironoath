// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一件装备（一个实例）的完整状态。注意这里没有「持有数量」这一位：**数量 = 本数组里同 `equipId` 的元素个数**，
 * 因为强化等级挂在件上之后，同 id 的两件不再等价，一个 count 说不清它们。
 */
public record EquipInstanceView(
        String uid,   // 实例号，服务端铸造，玩家生命周期内唯一。强化请求原样回传它 —— 它是「哪一件」的答案，而 `equipId` 只是「哪一行」。
        String equipId,   // `equip.json` 的行 id。给界面查名字与图标用，不参与任何判定。
        String name,   // 表里的中文名（改表立刻生效，客户端不硬编码装备名）。
        EquipSlot slot,   // 槽位，界面按它分组。
        EquipRarity rarity,   // 稀有度。
        int forgeLevel,   // 当前强化等级（+N）。0 是**合法的初始值**：新拿到的一件就是 +0。与科技等级「不存 0 占位」那条相反， 因为这里是逐件的账，省掉 0 就等于每次读列表都要区分「没穿过」与「没强化过」。
        int forgeMax,   // 这一件的强化上限，来自 `equip.json` 的 `forgeMax` 列（N=10 / SR=15）。下发它是为了让进度条有分母，而不是让客户端去查表。
        long mightFixed,   // **含强化**后的武力（定点万分比：12 点 = 120000）。算式 = 表里原值 ×(1 + 5% × forgeLevel)，与进入武将属性的那个数同一次计算产出 —— 界面显示的值和结算用的值必须是同一个数，否则玩家会说「我明明有 13 点武力」。
        long commandFixed,   // 含强化后的统率（定点万分比）。
        long wisdomFixed,   // 含强化后的智力（定点万分比）。
        long nextCostIron,   // 下一级要多少铁。已满级时为 0（与科技/国家科技同一口径：0 不表示免费，`blockReason=MAX_LEVEL` 才是要显示的话）。
        boolean canForge,   // 按钮亮不亮。
        EquipForgeBlockReason blockReason,   // 不亮的时候要说的那句话。
        String wornByHeroId)   // 穿在哪个武将身上；省略 = 在包里。**这一位是只读派生值**（从武将名档反查 uid 得到），不另存一份真相： 「谁穿着它」的权威只有一个，就是 `HeroInstance.equips` 里那个 uid。
{
}
