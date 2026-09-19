// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/rank/snapshot 的响应 —— 某一天某张榜的**全量**与分页（裁决③：运营侧走 ops 只读端点全量）。申诉时要能回答"那天第 37 名是多少分"，所以这里必须给出整榜而不是某一个人。
 */
public record OpsRankSnapshotResp(
        RankType type,   // 哪张榜。
        String dayKey,   // 日期键，yyyyMMdd（UTC+8）。
        long snapshotAt,   // 这份快照的拍摄时刻（毫秒）。
        List<RankEntryView> entries,   // 这一页的行，按名次升序。
        int totalPeople,   // 这一天这张榜上共有多少人（分页之外的第二信息：运营要一眼看出那天有多热闹）。
        int page,   // 请求的页码，原样回显。
        int pageSize,   // 本次实际生效的每页条数。
        boolean hasMore)   // 后面还有没有下一页。
{
}
