// 由 tools/config-gen 依据 contract/config/skill.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ironoath.common.json.FixedPointDeserializer;

/**
 * 配置表 skill 的一行。
 * 武将技能表。B02 要求字段：id/名称/触发时机/触发概率/效果类型/数值/持续回合。触发时机四种来自 B02 原文（回合开始/每回合/受击/死亡）。所有概率与数值都是定点小数（×10000 后存 long），配置里写成十进制字符串。技能由 hero 表的 mainSkill/subSkill 外键引用。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/skill.json} 的 fieldTypes 后运行 {@code npm run gen}。
 *
 * <p>标注为「定点数」的字段是真实值 ×10000 的 long（见 FixedPoint），
 * 配置表里写成十进制字符串，加载时由 FixedPointDeserializer 转成定点。<b>不要把它当真实值直接比较或输出</b>。
 */
public record SkillCfg(
        String id,   // 主键
        String name,
        Trigger trigger,   // 枚举，取值见 SkillTrigger
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long chance,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        Effect effect,   // 枚举，取值见 SkillEffect
        @JsonDeserialize(using = FixedPointDeserializer.class)
        long value,   // 定点数（真实值 ×10000）。配置里写成十进制字符串，生成后是 long，禁止还原成 double
        long durationRounds)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Trigger {
        ROUND_START,
        EVERY_ROUND,
        ON_HIT,
        ON_DEATH
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Effect {
        DAMAGE,
        HEAL,
        BUFF_ATK,
        BUFF_DEF,
        DEBUFF_ATK,
        DEBUFF_DEF,
        SKIP_TURN
    }

}
