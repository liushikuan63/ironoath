// 由 tools/config-gen 依据 contract/config/holiday.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 holiday 的一行。
 * 法定节假日日期表（未成年时长限制用）。一行 = 一个整天可玩的日期（UTC+8）。**id 就是日期本身**（yyyy-MM-dd），于是重复填同一个日期会被主键唯一性直接拦下 —— 这正是这张表最需要的性质。**预留表**：结构已定稿、数据由运营按年填（不是等后续批次）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/holiday.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record HolidayCfg(
        String id,   // 主键
        String name)
{
}
