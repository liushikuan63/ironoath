// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一场战斗的完整结果。<b>这就是战报</b>：存下来即可无限次重播，重播不需要重算（B05 §三）。seed 一并下发用于服务端自查、问题复现与反外挂校验，客户端不参与任何基于 seed 的计算。
 */
public record BattleResultView(
        BattleSide winner,
        BattleType battleType,
        int totalRounds,
        List<RoundView> rounds,
        List<UnitStack> attackerSurvivors,
        List<UnitStack> defenderSurvivors,
        long attackerDead,
        long attackerWounded,
        long attackerOverflowDead,   // 因医院超容量而死亡的伤兵。<b>必须单独下发</b>：B05 验收 7 要求「医院溢出：死亡数量与 UI 提示完全吻合」，如果把它并进 dead 里，玩家就看不到「有 300 个是因为医院不够而死」，也就没有升级医院的动机
        long defenderDead,
        long defenderWounded,
        long defenderOverflowDead,
        List<LootEntry> loot,   // 掠夺所得。按 resource 表顺序
        long lootCapacity,   // 本次负重上限（由兵种 load 决定）。UI 要显示「装满/未装满」
        long seed,
        long serverNow)
{
}
