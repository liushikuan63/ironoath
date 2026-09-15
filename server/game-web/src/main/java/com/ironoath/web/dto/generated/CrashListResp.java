// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/crash/recent 响应：最近的崩溃明细索引（只读，需运维令牌）。
 */
public record CrashListResp(
        long total,   // 当前存储里的崩溃总条数。**崩溃记录不按条数上限淘汰**，所以这个数只随保留期清理而下降。
        int listed,   // 本响应实际带出的条数。total 大于 listed 就说明还有没列出来的 —— 两个数分开给，是为了不让「翻了第一页」被读成「看到的全部」。
        List<CrashListItem> crashes)   // 按服务端收到时刻倒序，最多 limit 条。
{
}
