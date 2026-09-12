// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一回合的完整快照。客户端播一回合就是播这一个对象，不需要任何计算。
 */
public record RoundView(
        int round,
        List<UnitStack> attackerUnits,   // 回合开始时攻方的兵力构成
        List<UnitStack> defenderUnits,
        long attackerLoss,
        long defenderLoss,
        long attackerAttack,   // 攻方总攻击（已含全部乘区），供战报展开「这一回合为什么打这么多」
        long defenderDefense,
        long attritionFixed,   // 减员系数（定点）。B05 §1.4 的核心中间量，公示它才能让战报可解释
        List<SkillTriggerView> skills)
{
}
