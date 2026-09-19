// 由 tools/config-gen 依据 contract/config/battle_pass_season.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 battle_pass_season 的一行。
 * 每个赛季的战令限定外观（B24 块②）：买了本赛季战令就立即到手的那一枚头像框。一行一个赛季，id 就是 SeasonTimeline.seasonId() 的取值（由 season.json 的行 id 前缀推出，如 season_01_phase_3 ⇒ season_01）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/battle_pass_season.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BattlePassSeasonCfg(
        String id,   // 主键
        String frameId)   // 外键，指向 avatar_frame 表的 id
{
}
