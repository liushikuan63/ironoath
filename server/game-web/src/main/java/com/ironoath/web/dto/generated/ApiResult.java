// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 统一响应封装。code=0 表示成功，非 0 见 ErrorCode。traceId 用于线上排查（铁律 10）。
 */
public record ApiResult(
        int code,
        String msg,
        Object data,   // 业务负载，类型随接口而定
        String traceId,
        long serverNow,
        String detail)   // 错误详情，成功时为 null
{
}
