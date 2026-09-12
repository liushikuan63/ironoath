// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /player/init 请求体
 */
public record PlayerInitReq(
        String requestId,   // 幂等键。同一 requestId 重复提交只创建一次玩家（B01 验收 11）
        String deviceId,   // 设备唯一标识，同设备重复 init 返回同一存档
        String nickName,
        long clientTime)   // 客户端本地时间，服务端据此返回校准 offset
{
}
