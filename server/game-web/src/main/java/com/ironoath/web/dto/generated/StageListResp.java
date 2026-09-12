// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /stage/list 的响应：全部关卡的进度与解锁状态。
 */
public record StageListResp(
        List<StageEntry> stages,
        long stamina,   // 当前体力。与关卡一起下发是因为「能不能打这一关」同时取决于解锁状态与体力，分两个请求会让客户端自己拼这两个条件
        long serverNow)
{
}
