// 由 tools/config-gen 依据 contract/config/notice.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 notice 的一行。
 * 运营公告表（展示型；客户端热更白名单的第一张表）。**表里只有文案与生效窗口，不放任何数值** —— 数值一律由服务端算好再下发（铁律 1 与「客户端不抄配置表」的红线）。生效窗口由服务端按 serverNow 判，客户端不自己比时间（玩家时钟可改）。白名单口径见 global.HOT_UPDATE_TABLES（表级），本表是那张白名单里的第一张。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/notice.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record NoticeCfg(
        String id,   // 主键
        String title,
        String body,
        long startAt,
        long endAt,
        long priority)
{
}
