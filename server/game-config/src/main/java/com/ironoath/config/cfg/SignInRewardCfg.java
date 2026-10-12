// 由 tools/config-gen 依据 contract/config/sign_in_reward.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 sign_in_reward 的一行。
 * 2026-10-13用户批准的手动签到。北京时间04:00业务日；自然周从周一04:00、自然月从1日04:00开始。额外奖随当日签到整批接受，不与旧连续7/30日登录奖励叠加。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/sign_in_reward.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record SignInRewardCfg(
        String id,   // 主键
        String name,
        Period period,   // 枚举，取值见 SignInRewardPeriod
        Metric metric,   // 枚举，取值见 SignInRewardMetric
        Long targetDays,
        Long allowedMissDays,
        long rewardGold)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Period {
        DAILY,
        WEEKLY,
        MONTHLY
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Metric {
        TOTAL_DAYS,
        CONSECUTIVE_DAYS
    }

}
