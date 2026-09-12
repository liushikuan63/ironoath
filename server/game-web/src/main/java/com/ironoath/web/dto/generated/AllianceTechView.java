// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条联盟科技的当前进度（B10 §2：用联盟资金研究、全盟生效、上限随联盟等级）。
 */
public record AllianceTechView(
        String techId,   // `alliance_tech` 表的行 id。
        int level,   // 本盟已研究到的等级。表里 maxLevel 是绝对上限，实际还受联盟等级约束。
        int levelCap,   // 当前联盟等级下这一项的上限。<b>必须下发</b>：它是 maxLevel × (1 + alliance_config.techCapBonus) 的结果，客户端要查两张表再乘才能算出来，而自己算门槛正是本项目反复在防的那类漂移。
        long effectFixed)   // 累计效果值（定点，×10000）= 单级幅度 × 等级。<b>它只是「研究到的幅度」，不是最终生效的系数</b>：联盟科技该进哪个乘区、与个人科技/装备/编队加成是相加还是相乘，属于平衡口径（见收口清单 #31），定下来之前各消费方不擅自乘进去 —— 悄悄乘一遍的后果是「+1.5%/级 × 40 级」在某些组合下变成 +200%。
{
}
