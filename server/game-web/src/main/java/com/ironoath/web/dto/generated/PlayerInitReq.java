// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /player/init 请求体
 */
public record PlayerInitReq(
        String requestId,   // 幂等键。同一 requestId 重复提交只创建一次玩家（B01 验收 11）
        String deviceId,   // 设备唯一标识，同设备重复 init 返回同一存档
        String nickName,
        long clientTime,   // 客户端本地时间，服务端据此返回校准 offset
        String wxCode)   // 微信小游戏 wx.login 拿到的临时登录凭证（B15 §三）。服务端用它调 code2session 换 openid/session_key；不传时退回 deviceId 建档（浏览器与旧客户端）。开发环境用本地兑换器，真机走微信服务器
{
}
