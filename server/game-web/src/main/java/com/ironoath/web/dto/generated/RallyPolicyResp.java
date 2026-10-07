// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /rally/policy 响应体（B26 S13）：小队、联盟、国家三份政策一次给全（与 SocialCreatePolicyResp 同形状，少一次往返）。
 *
 * 【2026-10-07 更正（V22 口径① 裁决）】本字段原先写着「国家层级暂不在这里：B13 的国战集结还没有玩家入口，给了就是一个没人读的字段」—— 那句话的前提已经不存在：国家层现在有发起入口（`POST /rally/nation`），而客户端的灰键与界面上的人数上限<b>只能</b>从这份响应取。少给这一份的症状是两套数：客户端 `AppRoot.rallyPolicyOf` 对非 SQUAD 一律回联盟那一份，于是国家集结界面上亮着联盟的 20 人、服务端夹的却是国家的上限 ——「写口夹什么，读口就说什么」这条纪律在国家这一层会直接失效。原句留作理由记录，不删。
 */
public record RallyPolicyResp(
        RallyPolicyView squad,
        RallyPolicyView alliance,
        RallyPolicyView nation,
        long serverNow)   // 服务端时间戳
{
}
