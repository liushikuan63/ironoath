// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 建造队列视图。used 与 available 都下发，客户端据此显示「可开启第 N 队列」（B03 验收 7）。
 */
public record QueueView(
        int used,
        int available,   // 当前可用队列数，已含新手保护期的额外队列与特权队列
        int max)   // 队列上限（含特权），来源 city_rule_max_queue_count
{
}
