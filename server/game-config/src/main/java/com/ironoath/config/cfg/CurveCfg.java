// 由 tools/config-gen 依据 contract/config/curve.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 curve 的一行。
 * 成长曲线参数表（B00 数值速查「成长曲线」的唯一落地处）。运行期由 game-core 的 Formula 读取本表套公式，代码中不得出现任何曲线常量（铁律 1、跨语言一致性第 4 条）。base=0 表示基数由具体业务表逐行提供，本表只给比率与指数。小数一律写成十进制字符串，禁止 JSON number（见 contract/README.md）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/curve.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record CurveCfg(
        String id,   // 主键
        Kind kind,   // 枚举，取值见 CurveKind
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long base,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long ratio,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long exponent,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        Unit unit,   // 枚举，取值见 CurveUnit
        String formula)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Kind {
        GEOMETRIC,
        POWER
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Unit {
        SECOND,
        FIXED,
        FIXED_PER_HOUR,
        COUNT
    }

}
