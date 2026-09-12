// 由 tools/config-gen 依据 contract/config/gacha.json（表 version=3） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 gacha 的一行。
 * 抽卡表。B02 字段：池子/概率/保底次数/UP 内容，并强制包含概率公示字段 disclosureText。四档概率之和必须精确等于定点 1.0（10000），由单测强制校验。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/gacha.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record GachaCfg(
        String id,   // 主键
        String name,
        PoolType poolType,   // 枚举，取值见 GachaPoolType
        String costItemId,   // 外键，指向 item 表的 id
        String costResource,   // 外键，指向 resource 表的 id
        long costCount,
        long lifetimeLimit,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long ssrChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long ssrBaseChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long srChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long srBaseChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long rChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long rBaseChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long nChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long nBaseChance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long ssrPity,
        long srPity,
        String upHeroId,   // 外键，指向 hero 表的 id
        String disclosureText)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum PoolType {
        STANDARD,
        LIMITED,
        NEWBIE
    }

}
