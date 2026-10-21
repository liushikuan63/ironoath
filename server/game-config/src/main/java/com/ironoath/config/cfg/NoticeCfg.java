// 由 tools/config-gen 依据 contract/config/notice.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 notice 的一行。
 * 运营公告展示表（V26）。出厂为空，由运营填入真实文案后通过 /ops/config/reload 原子热更；测试用例自行注入窗口内外的夹具，不把未获确认的活动权益或过期样例发给玩家。
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
