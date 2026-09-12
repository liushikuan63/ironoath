// 由 tools/config-gen 依据 contract/config/equip_set.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 equip_set 的一行。
 * 装备套装表。一行 = 一个套装，给出 2 件套与 4 件套的百分比加成。装备本体在 equip 表，用 setId 关联。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/equip_set.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record EquipSetCfg(
        String id,   // 主键
        String name,
        Pieces2Attr pieces2Attr,   // 枚举，取值见 EquipSetPieces2Attr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long pieces2Ratio,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        Pieces4Attr pieces4Attr,   // 枚举，取值见 EquipSetPieces4Attr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long pieces4Ratio)   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Pieces2Attr {
        MIGHT,
        COMMAND,
        WISDOM
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Pieces4Attr {
        MIGHT,
        COMMAND,
        WISDOM
    }

}
