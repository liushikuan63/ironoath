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
        long effectFixed,   // 累计效果值（定点，×10000）= 单级幅度 × 等级。<b>它只是「研究到的幅度」，不是最终生效的系数</b>：联盟科技该进哪个乘区、与个人科技/装备/编队加成是相加还是相乘，属于平衡口径（见收口清单 #31），定下来之前各消费方不擅自乘进去 —— 悄悄乘一遍的后果是「+1.5%/级 × 40 级」在某些组合下变成 +200%。
        String name,   // 科技名（`alliance_tech` 表那一行的 name）。<b>显示名一律服务端下发</b>：客户端只有类型没有表数据，自己拿 techId 翻名字就是第二真源（同族见收口清单 #255 / #281 / #303）。
        long nextLevelCost,   // 再研究<b>一级</b>要多少联盟资金（与服务端 `Alliance.researchCost(base, id, 1)` 同一次计算）。没有这一项，玩家只能点了之后才知道钱不够 —— 而那一枪带幂等键、扣的是全盟公共资产。
        boolean canResearch,   // 这一项<b>此刻</b>研究得动吗：只判「没到本盟上限」与「资金够一级」两条（与写口同一句比较）。职位能不能研究不在这里判 —— 那份结论在 /social/permissions 的 RESEARCH_TECH 里，两处各说一件事，才不会互相打脸。
        String reason)   // 灰着的时候那句原因（玩家读得懂的话，不出现 id 与字段名）；能研究时为 null。
{
}
