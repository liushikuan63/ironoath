// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 榜上的一条。tag 只对组织榜有意义（联盟缩写），个人榜为 null —— 与联盟成员列表里的 tag 同一套来源。
 */
public record RankEntryView(
        int rank,   // 名次，从 1 起。
        String id,   // 主角 id：个人榜是 playerId，组织榜是 allianceId / nationId。
        String name,   // 显示名（服务端拼好下发）。
        long value,   // 榜值：POWER = MatchPower，KILL = 赛季击杀累计，组织榜 = 成员赛季分合计。
        String tag)   // 组织缩写；个人榜为 null。
{
}
