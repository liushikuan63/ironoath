// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 埋点上报结果。**部分失败是正常返回而不是错误**：埋点是尽力而为的数据，一批里有一条参数非法就让整批失败，等于用一条脏数据换掉九条好数据。
 */
public record TrackBatchResp(
        int accepted,   // 落库成功的事件数。
        int failed)   // 被丢弃的事件数 —— **两种原因合并计数**：事件名为空，以及超出入口软上限被截断。合并而不加第三个字段，是因为客户端对两者的处置完全相同（都不重投：一条脏数据重试一万次还是脏的，被截断的那一段则已经明确放弃了）。持续非零说明字典与实现已经漂移，或有人在直接刷这个不需要身份的端点，需要人去看而不是让它静默地一直失败。截断的累计条数由 GET /ops/ingest 单独带出。
{
}
