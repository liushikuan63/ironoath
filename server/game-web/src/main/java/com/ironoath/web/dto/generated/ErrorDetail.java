// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 结构化错误详情（B03 §2）。客户端直接拼成「还缺 XXX」，不显示笼统的「条件不足」。need 与 current 都必填，缺一即退化成笼统提示。
 */
public record ErrorDetail(
        String need,   // 需要什么，如「主城 8 级」「木材 12000」
        String current)   // 当前是什么，如「主城 6 级」「木材 3400」
{
}
