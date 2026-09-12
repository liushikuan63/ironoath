// 由 tools/config-gen 依据 contract/config/unit.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 unit 的一行。
 * 兵种表。四兵种 × T1~T5 共 20 行（用户确认阶级上限为 T5）。T2~T5 的攻击/防御/生命/训练耗时/训练消耗全部由 T1 基数按 curve.UNIT_STRENGTH 的比率 1.12^(tier-1) 推导后取整，不是独立拍出来的数 —— 改 T1 基数或改 1.12 就等于改全部五个阶级。速度与负载是兵种身份特征，不随阶级变化（T5 弓兵不该比 T1 弓兵跑得快，那会破坏「骑兵=机动」的辨识度）。克制关系不在本表，见 unit_counter 表。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/unit.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record UnitCfg(
        String id,   // 主键
        String name,
        Type type,   // 枚举，取值见 UnitType
        long tier,
        long attack,
        long defense,
        long hp,
        long speed,
        long load,
        long trainTimeSec,
        long trainCostWood,
        long trainCostIron,
        long trainCostGrain,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long vsBuildingBonus,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        String unlockBuilding,   // 外键，指向 building 表的 id
        long unlockBuildingLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Type {
        INFANTRY,
        CAVALRY,
        ARCHER,
        SIEGE
    }

}
