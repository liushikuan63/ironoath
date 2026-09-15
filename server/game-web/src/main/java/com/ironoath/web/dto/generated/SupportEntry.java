// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 客服与退款入口的配置（上线检查清单 §二 8/9：设置页一级可见、可跳转客服）。**配置为 null 时客户端仍然要显示入口**，点下去说明「本环境未配置客服」—— 把入口藏起来等于提审时「没有这个入口」，而那是要被打回的项。
 */
public record SupportEntry(
        String corpId,   // 企业微信客服的 corpId，wx.openCustomerServiceChat 的必填参数。来自环境变量 WECHAT_SUPPORT_CORP_ID —— 与 AppID 同族：它标识的是微信账号侧的配置，不是游戏数据，所以既不进配置表（表是玩法数值的家）也不写死在客户端。
        String url)   // 客服链接，wx.openCustomerServiceChat 的必填参数。来自环境变量 WECHAT_SUPPORT_URL。
{
}
