// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条入盟申请（B26 S8）。**昵称与主城等级由服务端一起下发**：审核要看的正是「这个人现在什么水平」，而客户端既没有玩家表也不该拿 id 去猜 —— 只回 id 的列表等于让盟主对着一串 p_1a2b 点批准。
 */
public record ApplicantView(
        String playerId,   // 申请人 id，审核时原样带回
        String nickname,   // 申请人昵称（服务端查的那一份，客户端不自己拼）
        int mainCityLevel)   // 申请人主城等级
{
}
