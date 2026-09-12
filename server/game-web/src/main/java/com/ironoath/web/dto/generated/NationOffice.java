// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国家官职（B13 §2）。取值必须与 game-core 的 Nation.Office 逐一对应，由 NationContractParityTest 断言 —— 复制而不校验才是真正的危险：漂移的症状是服务端认得的官职客户端显示成未知，UI 只会空白。
 *
 * 席位数：国王 1、首相 1、大将军 2、内政官 4、外交官 4，议员按盟主数动态给（不是固定席位）。
 */
public enum NationOffice {
    KING,
    PRIME_MINISTER,
    GENERAL,
    MINISTER,
    DIPLOMAT,
    REPRESENTATIVE
}
