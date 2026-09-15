// 由 tools/config-gen 依据 contract/config/alliance_tech.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 alliance_tech 的一行。
 * 联盟科技表。B02 字段：id/等级上限/消耗/效果。消耗单位为联盟捐献点数（B10 落地捐献系统）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/alliance_tech.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record AllianceTechCfg(
        String id,   // 主键
        String name,
        EffectAttr effectAttr,   // 枚举，取值见 AllianceTechEffectAttr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long effectValue,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long maxLevel,
        long costBaseDonation)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum EffectAttr {
        UNIT_ATTACK,
        UNIT_DEFENSE,
        MARCH_SPEED,
        LOAD_CAPACITY,
        HOSPITAL_CAPACITY,
        RALLY_CAPACITY,
        BUILD_SPEED,
        HELP_SPEED
    }

}
