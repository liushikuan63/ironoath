// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/treasury/spend 的响应。**回整条流水行**而不是只回余额：面板要立刻把这一笔显示出来，而它需要的就是日志那一行的四个字段（谁/何时/支给谁/多少）加余额。
 */
public record NationTreasurySpendResp(
        String nationId,   // 哪个国家。一次支取只动本国国库。
        long balance,   // 支出后的国库余额（国库资金单位）。
        String payee,   // 落点的文本形态（player:<id> / sink:<用途>），与日志的 counterparty 列同源。
        long amount,   // 这一笔的规模（正整数）。
        String reason,   // 用途说明，原样回显。
        TreasuryLogView log,   // 刚写入的那条流水（它的 balanceAfter 应当等于上面的 balance）。
        long serverNow)   // 服务端时刻。
{
}
