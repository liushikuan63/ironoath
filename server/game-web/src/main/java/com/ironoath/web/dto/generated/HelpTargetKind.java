// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 帮助的目标类型（B10 §2：升级 / 治疗可请求帮助）。两者共用同一个每日额度 global.HELP_DAILY_LIMIT —— 各给一份等于把加速总量翻倍，而 B10 禁止项明写「不要让小队互助与联盟帮助简单叠加」。
 */
public enum HelpTargetKind {
    BUILDING,
    TRAINING,
    TREATING
}
