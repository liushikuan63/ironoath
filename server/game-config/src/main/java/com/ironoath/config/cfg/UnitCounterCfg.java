// 由 tools/config-gen 依据 contract/config/unit_counter.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 unit_counter 的一行。
 * 兵种克制矩阵。B00 的「兵种克制」表是「谁克谁」的自然语言描述，直接塞进 unit 行会变成逗号分隔字符串，既没有类型安全也无法校验外键，所以拆成独立的关系表：一行 = 一条有向克制关系。加成与减益都是定点数，来自 global 表的 COUNTER_BONUS(+25%) 与 COUNTER_PENALTY(-20%)，本表允许逐对覆盖（留空则用全局默认）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/unit_counter.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record UnitCounterCfg(
        String id,   // 主键
        Attacker attacker,   // 枚举，取值见 UnitCounterAttacker
        Defender defender,   // 枚举，取值见 UnitCounterDefender
        @JsonDeserialize(using = FixedPointDeserializer.class)
        Long bonusFixed,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        @JsonDeserialize(using = FixedPointDeserializer.class)
        Long penaltyFixed)   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Attacker {
        INFANTRY,
        CAVALRY,
        ARCHER,
        SIEGE
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Defender {
        INFANTRY,
        CAVALRY,
        ARCHER,
        SIEGE,
        WALL,
        TRAP
    }

}
