// 由 tools/config-gen 依据 contract/config/bot_schedule.json（表 version=3） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 bot_schedule 的一行。
 * Bot 作息表（B11 交付数据）。一行 = 某小时某行为的相对权重，24 小时 × 若干行为。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/bot_schedule.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BotScheduleCfg(
        String id,   // 主键
        long hourOfDay,
        ActionType actionType,   // 枚举，取值见 BotScheduleActionType
        long weight,
        boolean isWeekendOnly)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum ActionType {
        LOGIN,
        LOGOUT,
        BUILD,
        TRAIN,
        GATHER,
        ATTACK_MONSTER,
        JOIN_RALLY,
        CHAT,
        DONATE
    }

}
