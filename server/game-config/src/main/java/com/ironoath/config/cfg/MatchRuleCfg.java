// 由 tools/config-gen 依据 contract/config/match_rule.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 match_rule 的一行。
 * 战力匹配与保护规则表（B08 依赖）。两类行：PROTECTION 是保护规则，SCENARIO 是 PVP 场景。本表不重复写任何数值，全部用 *Param 字段外键引用 global 表的参数 id —— 同一个数字只能有一个家，两处都写一定会不同步。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/match_rule.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record MatchRuleCfg(
        String id,   // 主键
        Kind kind,   // 枚举，取值见 MatchRuleKind
        String triggerCond,
        String durationParam,   // 外键，指向 global 表的 id
        String triggerCountParam,   // 外键，指向 global 表的 id
        String powerMinParam,   // 外键，指向 global 表的 id
        String powerMaxParam,   // 外键，指向 global 表的 id
        String bonusParam,   // 外键，指向 global 表的 id
        boolean blocksActiveAttack,
        String windowParam,   // 外键，指向 global 表的 id
        String durationParam2,   // 外键，指向 global 表的 id
        String triggerCountParam2)   // 外键，指向 global 表的 id
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Kind {
        PROTECTION,
        SCENARIO
    }

}
