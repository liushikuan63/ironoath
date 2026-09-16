// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一步在什么时候弹出来。**只有真被表用到的两个取值**：不预声明「首次进入战斗」这类没人用的触发器（B18 禁止项）。
 */
public enum GuideTrigger {
    PANEL_OPEN,
    STATE_REACHED
}
