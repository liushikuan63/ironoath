// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /gacha/draw 响应体。seed 回传用于可复现（B06 验收 11：同 seed 同结果）与客服核查；两个保底计数器都回传，客户端要显示「还差几抽保底」。
 */
public record GachaDrawResp(
        List<GachaResult> results,
        long ssrPityCounter,   // 距上次出 SSR 已累计多少抽
        long srPityCounter,
        long fragmentsAwarded,   // 本次重复武将转化的碎片总数
        String costItemId,   // 以道具计价的池子（限定池）填这里，否则为 null。与 costResource 恰好一个非空。
        long costCount,
        long seed,
        long serverNow,
        String costResource)   // 以资源计价的池子（新手池/标准池填 GOLD）填这里，否则为 null。与 costItemId 恰好一个非空 —— 两者都填或都不填都是配置错误，由 GachaConfigConsistencyTest 断言。
{
}
