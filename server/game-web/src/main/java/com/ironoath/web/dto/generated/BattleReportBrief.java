// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战报列表里的一条摘要。**不含 rounds** —— 一场 8 回合的战斗逐回合展开有几十个数字，列表页只要「打赢了没有、损失多少、什么时候打的」。把 rounds 塞进列表会让一次「看看最近的战报」变成几百 KB 的下发，而 B07 为地图视野定的 20KB 上限就是为了让弱网玩家不被一次响应卡住，战报列表没有理由例外。
 */
public record BattleReportBrief(
        String reportId,
        BattleType battleType,
        String opponentId,   // 对手 id。打野时是 mapmonster 的行 id，PVP 时是对方玩家 id
        String opponentName,   // 对手显示名，服务端下发。客户端不得自行翻译或拼接：「LV12 野蛮人营地」这种名字是策划在表里写的，客户端自己拼就会与服务端日志、客服工单里的称呼对不上
        BattleSide winner,
        boolean won,   // 我方是否获胜。冗余于 winner，但列表页的「胜/败」标签不该让客户端自己去判断「winner==ATTACKER 且我是攻方」—— 那个判断需要知道自己是哪一方，而列表项里没有这个信息
        int totalRounds,
        long attackerLoss,
        long defenderLoss,
        long createdAt,
        long expiresAt)   // 过期时刻。过期的战报会被清理（惰性，不跑定时器），下发这个字段是为了让客户端能置灰「即将失效」的条目，而不是让玩家点开一条已经被清掉的战报再收到一个错误
{
}
