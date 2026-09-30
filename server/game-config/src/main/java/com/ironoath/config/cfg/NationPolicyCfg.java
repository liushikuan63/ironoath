// 由 tools/config-gen 依据 contract/config/nation_policy.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 nation_policy 的一行。
 * 国策表（B13 §4 / B21 §五④）。与国家科技（nation_tech.json）的关系：同一层级的两种全国性加成，但**机制完全相反** —— 国家科技是花钱买的永久加成，国策是投票选出来的、每轮换一次、到点就失效的周期 buff。所以这张表没有等级、没有成本曲线、没有费用：一条国策就是「一个效果 + 一个幅度 + 一个生效槽位」，其余全在投票流程里。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/nation_policy.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record NationPolicyCfg(
        String id,   // 主键
        String name,
        EffectAttr effectAttr,   // 枚举，取值见 NationPolicyEffectAttr
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long effectValue,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        String targetUnit,   // 外键，指向 unit 表的 id
        long requireNationLevel)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum EffectAttr {
        POLICY_ATTACK,
        POLICY_DEFENSE,
        OUTPUT,
        MARCH_SPEED
    }

}
