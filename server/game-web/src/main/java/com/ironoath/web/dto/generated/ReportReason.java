// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 举报原因（B22 §一 3）。**是枚举而不是自由文本**：留痕表要能按原因聚合（"这周辱骂举报涨了多少"），自由文本答不了这个问题；而玩家要补充的细节另有 detail 字段。CHEAT_SUSPECT 只表示"玩家怀疑"，判定归运营与反作弊，代码不替它下结论。
 */
public enum ReportReason {
    ABUSE,
    SPAM,
    CHEAT_SUSPECT,
    OTHER
}
