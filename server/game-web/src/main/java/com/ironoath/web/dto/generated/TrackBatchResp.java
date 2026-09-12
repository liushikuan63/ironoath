// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 埋点上报结果。**部分失败是正常返回而不是错误**：埋点是尽力而为的数据，一批里有一条参数非法就让整批失败，等于用一条脏数据换掉九条好数据。
 */
public record TrackBatchResp(
        int accepted,   // 落库成功的事件数。
        int failed)   // 被丢弃的事件数。客户端据此判断埋点健康度：这个数持续非零说明字典与实现已经漂移（例如某个事件名被运营删了而客户端还在发），需要人去看，而不是让它静默地一直失败。
{
}
