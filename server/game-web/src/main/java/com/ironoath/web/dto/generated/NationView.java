// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个国家的公开视图。
 */
public record NationView(
        String nationId,   // 国家 id。
        String name,   // 国名。建国时由发起人取，敏感词校验在服务端（B15 合规）。
        String kingId,   // 国王的玩家 id。建国者直接担任（B13 §五 开放问题 1 的当前裁定：不做联盟间竞选）。
        int level,   // 国家等级，来自 nation_config 表。等级决定人数上限、国库上限与国策槽位数。
        int allianceCount,   // 已入籍的联盟数。**国家成员表的最小单位是联盟**，所以这里给联盟数而不是玩家数 —— 玩家名单在联盟那一侧，两处各存一份迟早对不上。
        int memberCap,   // 人数上限（nation_config.memberCap）。国家容量 = Σ成员联盟容量，并受这个上限约束。
        long capitalX,   // 都城横坐标（格）。
        long capitalY,   // 都城纵坐标（格）。
        long treasury,   // 国库余额。来源是成员联盟按周上缴的税收（global.NATION_TAX_WEEKLY_PER_ALLIANCE）。
        long treasuryCap,   // 国库上限（nation_config.treasuryCap）。达到上限后税收不再入账 —— 这条必须下发，否则玩家会以为税收被吞了。
        String myOffice,   // 请求者本人担任的官职，没有则为 null。**刻意用官职名的字符串而不是 NationOffice 枚举**：客户端要显示的是「大将军」这样的中文名，而那份文案在客户端的本地化表里；服务端下发枚举名，客户端据此查表 —— 下发中文的话，改一次文案就要改服务端配置表。
        Long serverNow)   // 服务端时间戳（铁律 5）。
{
}
