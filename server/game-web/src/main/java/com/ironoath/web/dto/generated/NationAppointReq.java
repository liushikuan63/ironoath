// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/appoint 请求体：任命官职。
 *
 * **权限走 role_permission 表**（perm_nation_appoint_office：只有国王能任命）。被任命者必须属于某个已入籍的联盟 —— 个人不能脱离联盟单独入籍，所以也不能被单独任命。
 */
public record NationAppointReq(
        String requestId,   // 幂等键。
        String playerId,   // 被任命者。<b>服务端会拒绝 Bot</b>（B13 §2 合规红线：Bot 不得担任任何国家官职）。这条判定不在协议里表达 —— 协议里没有任何字段能让客户端声明「这个人是真人」，所以客户端无法绕过。
        NationOffice office)   // 要任命的官职。席位数由 Nation.Office.seatCount() 决定，议员按盟主数动态给。
{
}
