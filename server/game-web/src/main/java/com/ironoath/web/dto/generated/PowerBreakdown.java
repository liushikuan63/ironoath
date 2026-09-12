// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战力明细（B08 §1）。**UI 必须能点开看这一层** —— 玩家对「我为什么是这个战力」极度敏感，只给一个总数的话，任何一次数值调整都会被理解成「偷偷削我」。
 *
 * 五项之和必须精确等于 displayPower，这条不变量在服务端由 PowerCalculator.Result 的构造器强制（对不上就抛异常），所以客户端可以放心把五行逐项列出来再加一个总计。
 */
public record PowerBreakdown(
        long building,   // 建筑战力：各建筑按 POWER_CONTRIB 曲线的贡献之和
        long troops,   // 部队战力：Σ(兵数 × 该兵种的攻+防+血)
        long heroes,   // 武将战力：全部已拥有武将之和，含未上阵的 —— 展示战力要反映「我练了多少」
        long tech,   // 科技战力。科技系统属 B12，落地前恒为 0；字段先占位，否则 B12 上线时要改协议，而改协议意味着强制客户端更新
        long equipment)   // 装备战力。当前已并入 heroes（HeroCalculator 的入参含装备固定值），所以恒为 0；单列一项是为了让明细的行数与玩家的直觉一致 —— 玩家认为装备是独立的一块，看不到这一行会以为装备没算进去
{
}
