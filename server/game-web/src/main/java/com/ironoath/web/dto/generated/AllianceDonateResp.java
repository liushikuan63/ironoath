// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /alliance/donate 响应体（B10 §二 + 验收 8：捐献后资金与贡献值同步增加）。
 */
public record AllianceDonateResp(
        long fundGained,   // 本次给联盟的资金
        long contributionGained,   // 本次给我的贡献值
        long fund,   // 捐献后的联盟资金总额。**必须下发**：只给增量的话客户端要自己累加，而累加一旦与服务端不同步就再也对不上了（验收 8 要求两者同步增加）
        long contribution,   // 捐献后的我的贡献值总额
        int donateToday,   // 今天已捐档数
        int donateDailyCap,   // 每日捐献档数上限（alliance_config.donationDailyCap）
        long serverNow)   // 服务端时间戳
{
}
