// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 目标资源富度的提示（B08 §8）。同样只给档位不给数字：给数字等于把对方的仓库状态下发出去，而那应当是侦查才能拿到的情报 —— 否则 B07 §3 的「情报有误差」就失去意义了。
 */
public enum ResourceHint {
    RICH,
    NORMAL,
    POOR
}
