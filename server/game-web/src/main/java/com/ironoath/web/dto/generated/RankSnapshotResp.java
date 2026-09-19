// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /rank/snapshot 的响应 —— 某一天的每日快照里**我的那一行**。刻意没有 entries：裁决③（可见性）定的是"玩家只能查自己"，全服历史名次是情报（与"不下发精确距离"同一条思路）。运营要全量走 /ops/rank/snapshot。
 */
public record RankSnapshotResp(
        RankType type,   // 查的是哪个榜。
        String dayKey,   // 日期键，yyyyMMdd（UTC+8，与全项目同一个 DayKey —— 不许出现第二个日切轴）。
        long snapshotAt,   // 这一份快照的拍摄时刻（毫秒）。同一天重复读不会刷新它 —— 「同一天只拍一份」的唯一可证形态。
        Integer myRank,   // 我在那一天的榜上名次；那天榜上没有我时为 null（不许用 0 冒充）。
        Long myValue)   // 我在那一天的榜值；未上榜为 null。
{
}
