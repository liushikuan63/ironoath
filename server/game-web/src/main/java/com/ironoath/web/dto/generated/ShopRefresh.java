// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 限购的刷新口径。取值与 `ShopCfg.RefreshType` 一致。
 *
 * **为什么这一列必须是枚举而不是「limitCount 每天几次」这种约定**：NONE 与 DAILY 在表里长得一样（都是 limitCount=20），差别只在什么时候清零，而这决定了玩家什么时候能再买一次。服务端算限购时读的就是这一列，两种周期共用同一个计数组件、把**周期标签**作为键的一段（日切用 `DayKey`、周口径用 `WeekKey`、赛季口径用当前赛季 id，三者共用同一个日历口径）—— 周期标签已经在键里，所以键只会因跨期而换新，绝不会因为 TTL 到点而在期内提前刷新。
 */
public enum ShopRefresh {
    NONE,
    DAILY,
    WEEKLY,
    SEASON
}
