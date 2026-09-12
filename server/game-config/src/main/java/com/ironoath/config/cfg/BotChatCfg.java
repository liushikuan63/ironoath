// 由 tools/config-gen 依据 contract/config/bot_chat.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 bot_chat 的一行。
 * Bot 聊天语料表（B11 交付数据）。scene 决定这句话在什么场合出现，minCityLevel/maxCityLevel 限定说话者的等级区间。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/bot_chat.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BotChatCfg(
        String id,   // 主键
        Scene scene,   // 枚举，取值见 BotChatScene
        String text,
        long weight,
        long minCityLevel,
        Long maxCityLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Scene {
        HELP_REQUEST,
        RALLY_CALL,
        ATTACKED,
        VICTORY,
        DEFEAT,
        CHAT_IDLE,
        ALLIANCE_JOIN,
        TRADE
    }

}
