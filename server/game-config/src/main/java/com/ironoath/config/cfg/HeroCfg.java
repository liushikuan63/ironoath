// 由 tools/config-gen 依据 contract/config/hero.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 hero 的一行。
 * 武将表。B02 要求字段：id/名称/稀有度/初始三维/成长率/主技能/副技能/缘分/觉醒上限。稀有度四档 N/R/SR/SSR。三维为 武力(might)/统率(command)/智力(wisdom)。全部名称为原创，与任何既有作品无对应关系（B00 版权合规要求）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/hero.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record HeroCfg(
        String id,   // 主键
        String name,
        Rarity rarity,   // 枚举，取值见 HeroRarity
        long might,
        long command,
        long wisdom,
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long growthRate,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long maxLevel,
        String mainSkill,   // 外键，指向 skill 表的 id
        String subSkill,   // 外键，指向 skill 表的 id
        String bondWith,   // 外键，指向 hero 表的 id
        long awakenMax)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Rarity {
        N,
        R,
        SR,
        SSR
    }

}
