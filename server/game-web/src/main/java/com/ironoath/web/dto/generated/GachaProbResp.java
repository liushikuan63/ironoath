// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /gacha/probability 响应体。disclosureText 必须原文展示（gacha 表已注明：不得删减、折叠或以图标替代）。
 */
public record GachaProbResp(
        String poolId,
        String name,
        String poolType,
        List<GachaProbItem> items,   // 逐个武将的概率。限定池的 UP 武将单列，其余同稀有度武将平分该档剩余概率
        List<TierRate> tierRates,   // 四档概率（SSR/SR/R/N），之和必须恰为 10000（B02 已把这条做成 CI 断言）
        PityRule pityRule,
        String disclosureText,   // 合规公示原文，客户端必须原样展示
        String costItemId,   // 以道具计价的池子（限定池）填这里，否则为 null。与 costResource 恰好一个非空。
        long costCount,   // 单抽消耗
        long lifetimeLimit,   // 该池的账号终身抽取次数上限；0 表示不限
        long serverNow,
        String costResource)   // 以资源计价的池子（新手池/标准池填 GOLD）填这里，否则为 null。与 costItemId 恰好一个非空 —— 两者都填或都不填都是配置错误，由 GachaConfigConsistencyTest 断言。
{
}
