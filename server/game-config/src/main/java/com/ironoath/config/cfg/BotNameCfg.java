// 由 tools/config-gen 依据 contract/config/bot_name.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 配置表 bot_name 的一行。
 * Bot 名字池（B11 交付数据）。namePool 是字符串数组，按 weight 抽池、池内等概率取名。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/bot_name.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record BotNameCfg(
        String id,   // 主键
        JsonNode namePool,   // 任意结构
        long weight,
        String culture)
{
}
