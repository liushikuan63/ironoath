// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一次技能触发，供战报 UI 展示与播放技能特效。
 */
public record SkillTriggerView(
        SkillPhase phase,
        String skillId,
        String skillName,   // 中文名，来自 skill 表，客户端不得自行翻译
        String side,   // 由哪一方触发
        String heroId,   // 触发的武将；null 表示非武将来源
        Long valueFixed)   // 效果值（定点 ×10000）
{
}
