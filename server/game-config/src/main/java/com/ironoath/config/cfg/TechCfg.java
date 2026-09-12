// 由 tools/config-gen 依据 contract/config/tech.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 tech 的一行。
 * 科技表。B02 字段：id/名称/所属学派/等级上限/消耗/效果。四学派对应 B00 的四种资源与三条玩法线（内政/军事/经济/工事）。每级消耗按 costCurve 指向的曲线递增（TECH_TIME 比率 1.28，是全项目最陡的），effectValue 是每级增益（定点小数）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/tech.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record TechCfg(
        String id,   // 主键
        String name,
        School school,   // 枚举，取值见 TechSchool
        long maxLevel,
        EffectAttr effectAttr,   // 枚举，取值见 TechEffectAttr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long effectValue,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long costBaseWood,
        long costBaseStone,
        long costBaseIron,
        long costBaseGrain,
        String costCurve,   // 外键，指向 curve 表的 id
        long requireAcademyLevel)
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
        WOOD_OUTPUT,
        STONE_OUTPUT,
        IRON_OUTPUT,
        GRAIN_OUTPUT,
        UNIT_ATTACK,
        UNIT_DEFENSE,
        MARCH_SPEED,
        TRAIN_SPEED,
        BUILD_SPEED,
        HOSPITAL_CAPACITY,
        LOAD_CAPACITY
    }

}
