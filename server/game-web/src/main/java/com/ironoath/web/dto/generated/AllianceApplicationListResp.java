// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /alliance/applications 响应体（B26 S8）：**本盟**待处理申请的前 limit 条。存在的理由与另两份发现口同族 —— `/alliance/review` 早就有，但没有任何地方能列出「谁申了」，于是那颗批准按钮永远按不下去。这一条**只对能审核的人开**（服务端按 role_permission 的 APPROVE_APPLICATION 判），因为它下发的是别人的身份。
 */
public record AllianceApplicationListResp(
        List<ApplicantView> applicants,   // 行，按申请人 id 升序（同一份库存上可复现）
        int total,   // 本盟待处理申请总数（与徽标那个数同源，不是本页条数）
        int limit,   // 本次实际生效的条数上限（global.ALLIANCE_APPLICATION_LIST_LIMIT）
        long serverNow)   // 服务端时间戳
{
}
