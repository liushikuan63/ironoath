// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/sync 请求体（B10 验收 10：成员数据变更只下发 diff，不全量同步）。
 */
public record AllianceSyncReq(
        long version,   // 客户端手里的联盟数据版本号。首次同步传 0
        boolean wantMembers)   // 是否要成员列表的 diff。只想看资金与等级时传 false —— 150 人的成员列表是联盟数据里最大的一块，每次心跳都带上它就是把 diff 同步的意义抵消掉
{
}
