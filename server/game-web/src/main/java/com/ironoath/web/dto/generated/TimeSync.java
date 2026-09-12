// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 时间校准结果。offset = serverNow - clientNow，客户端用加权移动平均吸收网络抖动（B00 铁律 5）。
 */
public record TimeSync(
        long offset,
        long syncAt)   // 服务端发出本次校准的时间戳
{
}
