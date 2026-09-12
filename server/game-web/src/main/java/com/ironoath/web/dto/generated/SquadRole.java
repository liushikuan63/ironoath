// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 小队职位（B10 §4 权限矩阵的 squad scope）。只有两级：小队 5~10 人，再多一层职位就是官僚主义 —— C00 公理七指出小队满足的是「我和兄弟们」，熟人圈子里没有副队长。
 */
public enum SquadRole {
    LEADER,
    MEMBER
}
