// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /nation/treasury 的响应（B13 §二 的 {@code TreasuryResp}）。
 *
 * <b>谁能读</b>：本国任一成员联盟的成员 —— 这就是「防贪污」的全部机制所在，只给国王看等于没有。
 *
 * <b>为什么把余额与流水放在一起回</b>：面板要同时显示这两个，而分两次查就会得到两个时刻的数（余额与流水对不上，正是这条表唯一要防的那种形状）。国库容量在 {@code NationView} 里已有，这里不重复第二次。
 */
public record NationTreasuryResp(
        long balance,   // 当前国库余额（国库资金单位，与 GOLD 同单位）。它是**现算后**的数：读国库这个动作会先把当周周税结清，理由与 {@code GET /nation} 同一条 —— 玩家看到的就该是当下的数。
        List<TreasuryLogView> logs,   // 流水，<b>按时间倒序</b>（最新在前，面板直接取前几条）。条数由 global.NATION_TREASURY_LOG_RETENTION 在领域层截断并保留最新的，协议不重复那个决定，只做透传。
        long serverNow)   // 服务端时刻。与流水里的 {@code at} 同源，客户端据此算「三分钟前」这类相对时间。
{
}
