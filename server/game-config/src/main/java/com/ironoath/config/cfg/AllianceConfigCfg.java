// 由 tools/config-gen 依据 contract/config/alliance_config.json（表 version=3） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 alliance_config 的一行。
 * 联盟配置表（B10 交付数据）。按联盟等级给出人数上限、领地容量、集结容量、科技加成。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/alliance_config.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record AllianceConfigCfg(
        String id,   // 主键
        long allianceLevel,
        long memberCap,
        long unlockMainLevel,
        long unlockDayOffset,
        long territoryCap,
        long rallyCapacity,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long techCapBonus,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long donationDailyCap)
{
}
