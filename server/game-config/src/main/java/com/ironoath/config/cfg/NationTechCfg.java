// 由 tools/config-gen 依据 contract/config/nation_tech.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 nation_tech 的一行。
 * 国家科技表（B20 块③）。与个人科技（tech.json）的关系：同一套 effectAttr 词汇、同一条 costCurve 曲线族，但**出资方与承载方都不同** —— 钱出自国库（Nation.Sink.NATIONAL_TECH 核销），等级记在国家上而不是个人身上，生效范围是全国成员。每学派一行、共 4 行（§五③）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/nation_tech.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record NationTechCfg(
        String id,   // 主键
        String name,
        School school,   // 枚举，取值见 NationTechSchool
        EffectAttr effectAttr,   // 枚举，取值见 NationTechEffectAttr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long effectValue,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long maxLevel,
        long costBaseTreasury,
        String costCurve,   // 外键，指向 curve 表的 id
        long requireNationLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum School {
        AGRICULTURE,
        MILITARY,
        COMMERCE,
        FORTIFICATION
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum EffectAttr {
        GRAIN_OUTPUT,
        TRAIN_SPEED,
        MARCH_SPEED,
        BUILD_SPEED
    }

}
