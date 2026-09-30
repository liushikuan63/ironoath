// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * `POST /nation/policy/propose` 请求体：把一条国策放进本轮提案池。
 *
 * **权限走 `role_permission` 表的 `SET_NATIONAL_POLICY`**（2026-09-30 裁决 A1 把 `allowOfficer` 从 false 改成 true，即国王与四类官员都可提案；`B13:49` 的「议员提案」随之退役）。
 *
 * **提案不消耗国库**：`TreasurySink` 只有 `NATIONAL_TECH` 与 `WAR_BOOST` 两值（B13 §3 的国库三用途在 2026-09-11 收敛过一次），国策不在其中 —— 所以这个请求里没有任何金额字段。
 */
public record NationPolicyProposeReq(
        String requestId,   // 幂等键。重放一次提案不该在本轮池里出现两条 —— 那会让公示的票数分母与提案数对不上，而公示的争议正是从这里开始的。
        String policyId)   // 提哪一条（`nation_policy.json` 的行 id）。服务端依次校验：行存在 → 身份有提案权 → 现在是提案段 → 本轮还没提过这一条。
{
}
