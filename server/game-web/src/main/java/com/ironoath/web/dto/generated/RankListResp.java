// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /rank/list 与 /rank/me 的响应（同形：/rank/me 只带"我的那一行"与我的名次）。myRank 为 null 表示未上榜，不许用 0 冒充（B23 验收 2，与赛季 myRank 同一条纪律：0 会与"第 0 名"混淆，而名次从 1 起）。
 */
public record RankListResp(
        RankType type,   // 这一页是哪个榜。
        List<RankEntryView> entries,   // 这一页的行，按名次升序。
        Integer myRank,   // 我的名次；未上榜为 null。
        Long myValue,   // 我的榜值；未上榜为 null。
        int page,   // 请求的页码，原样回显（B23 验收 6 的体积判据要靠它复现同一页）。
        int pageSize,   // 本次实际生效的每页条数（服务端夹过：上限来自 global.RANK_PAGE_SIZE_MAX）。
        boolean hasMore)   // 后面还有没有下一页。
{
}
