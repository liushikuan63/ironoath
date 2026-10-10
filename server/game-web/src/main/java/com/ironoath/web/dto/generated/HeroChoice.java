// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 三选一里的一个候选。**id 与 name 成对下发**：不给两条平行数组（ids 与 names）是因为它们迟早会不同长，而不同长的表现是「点了第三个选项却领到第一个武将」，全链路不报错。
 * 名称、基础三维和技能说明由服务端解析真实 hero/skill 配置，客户端不查表、不推导强度。新增字段可选且可空，兼容只有 id/name 的旧响应；选择与领取仍只回传 heroId。
 */
public record HeroChoice(
        String heroId,   // hero 表的行 id。选它之后随 claim 的 heroChoice 回传。
        String name,   // 武将名（hero 表的 name）；缺名字安全回退未知武将，不以内部 id 展示。
        String rarity,   // 真实 hero.rarity 的既有 N/R/SR/SSR 档记号；缺配置为空，不发明新档名。
        Long might,   // hero.might 基础武力原值，不含等级、装备或编队加成；缺配置为空。
        Long command,   // hero.command 基础统率原值；缺配置为空。
        Long wisdom,   // hero.wisdom 基础智力原值；缺配置为空。
        String mainSkillName,   // 主技能的 skill.name 中文名，缺配置显示未知技能。
        String mainSkillDescription,   // 主技能基础配置的玩家说明：实际触发时机、概率、效果、幅度与适用持续回合，不下发设计 why 或裸枚举；缺信息显示技能信息暂不可用。
        String subSkillName,   // 副技能的 skill.name 中文名，缺配置显示未知技能。
        String subSkillDescription,   // 副技能基础配置的玩家说明，与主技能同口径。
        String featureText)   // 从实际主、副技能效果派生的简短特点，不使用 hero/skill why，不给武将新增定位或强度数值。
{
}
