// 由 tools/config-gen 依据 contract/config/bot_archetype.json（表 version=4） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 bot_archetype 的一行。
 * Bot 原型表（B11 交付数据）。本批次只定稿结构。每个 Bot 实例绑定一个原型，并在原型的区间内取自己的独立参数。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/bot_archetype.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record BotArchetypeCfg(
        String id,   // 主键
        String name,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long aggression,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long socialness,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long greed,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long powerFactor,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long growthFactorMin,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long growthFactorMax,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long reactionDelayMinSec,
        long reactionDelayMaxSec,
        long helpDelayMinSec,
        long helpDelayMaxSec,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long suboptimalChanceMin,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long suboptimalChanceMax,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        String activeHoursPattern,
        PlayStyle playStyle)   // 枚举，取值见 BotArchetypePlayStyle
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum PlayStyle {
        FARMER,
        RAIDER,
        BUILDER,
        SOCIAL,
        MIXED
    }

}
