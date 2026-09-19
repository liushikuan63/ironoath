// 由 tools/config-gen 依据 contract/config/battle_pass.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 battle_pass 的一行。
 * 赛季战令的档位表（B24 块②）。20 档、每档 150 分，两轨奖励各一行 —— 免费线给资源与加速，付费线给金币与抽卡券。本表**不含赛季字段**：进度按赛季隔离（键取 SeasonTimeline.seasonId()），换赛季只是换一本账，不需要清理任务；而本赛季的限定外观**不在这张表里**（它随购买立即到账，见 battle_pass_season.json）——把外观写成第 20 档奖励会让"买了却拿不到"变成一种正常状态。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/battle_pass.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BattlePassCfg(
        String id,   // 主键
        long tier,
        long requiredPoints,
        FreeRewardType freeRewardType,   // 枚举，取值见 BattlePassFreeRewardType
        String freeRewardId,
        long freeRewardCount,
        PaidRewardType paidRewardType,   // 枚举，取值见 BattlePassPaidRewardType
        String paidRewardId,
        long paidRewardCount)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum FreeRewardType {
        RESOURCE,
        ITEM
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum PaidRewardType {
        RESOURCE,
        ITEM
    }

}
