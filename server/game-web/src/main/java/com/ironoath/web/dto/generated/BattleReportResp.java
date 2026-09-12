// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /battle/report 的响应：一场战斗的完整回放数据。
 *
 * **rounds 必须逐回合完整下发**：B05 交付的客户端 BattlePlayback 就是按这个结构做时间轴的（1x/2x/跳过），少一个字段回放就会跳帧。而战报只存 seed + 输入就能由服务端 100% 复算（铁律 4），所以下发完整 rounds 不是「把计算结果泄露给客户端」——客户端本来就可以自己重放，服务端下发只是省掉它一次重算。
 *
 * **刻意不下发双方的完整属性**：RoundView 里只有每回合的兵力、损失、有效攻击/防御与减员系数，没有对方的兵种属性表与武将明细。回放需要的是「发生了什么」，不是「对方有多强」—— 后者是侦查的职责（B07 §3），白送会让情报系统失去意义。
 */
public record BattleReportResp(
        String reportId,
        BattleResultView result,
        long createdAt,
        long expiresAt,
        long serverNow)
{
}
