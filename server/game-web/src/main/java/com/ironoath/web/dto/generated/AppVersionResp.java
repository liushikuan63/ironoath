// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 版本检查结果（验收 8：低版本客户端收到强制更新提示且无法进入游戏）。
 */
public record AppVersionResp(
        String latest,   // 最新客户端版本，来自 global.RELEASE_LATEST_VERSION。
        boolean forceUpdate,   // 是否需要强制更新。true 时客户端必须停在提示页，不得进入游戏 —— 放行一个低于 minSupported 的版本等于让它在服务器上写坏数据，而那种损坏在玩家更新之后才会显现，届时已经无法归因。
        boolean grayEnabled,   // 该玩家是否落在灰度批次内（global.RELEASE_GRAY_PERCENT）。**与 forceUpdate 正交**：灰度 5% 不等于另外 95% 的人不能玩。同一个玩家的判定结果每次一致（稳定哈希），否则他刷新一次就可能从灰度里掉出去，而「刚才能玩现在不能玩」是最难排查的一类投诉。
        String notice,   // 强制更新的提示文案（global.RELEASE_FORCE_UPDATE_NOTICE），forceUpdate=false 时为 null —— 不强制更新时不该打扰玩家。文案必须说明「为什么」：只说「请更新」的话，被挡在门外的玩家会以为游戏坏了而直接卸载。
        TrackPolicy trackPolicy,   // 本次会话应当使用的埋点攒批策略。<b>必填而不是可空</b>：客户端从第一个事件（startup）开始就要按策略攒批，而版本检查正是启动的第一个请求，所以策略在这一刻必然已经拿到。做成可空的话客户端就必须准备一套兜底数字，而那套兜底数字正是铁律 1 禁止的硬编码 —— 更糟的是它会与服务端悄悄漂移。
        SupportEntry support)   // 客服与退款入口的配置；本环境未配置时为 null。**刻意是可选字段而不是 required**：滚动升级期间旧服务端不会下发它，而客户端把「没有这个字段」读作「未配置」，两边都能跑。
{
}
