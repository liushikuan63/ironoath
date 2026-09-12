// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 消耗性用途（B13 §3 原文的两个：国家科技 / 国战增益）。名字进日志的 counterparty 列（形如 sink:NATIONAL_TECH），所以可以直接与文档对照。
 */
public enum TreasurySink {
    NATIONAL_TECH,
    WAR_BOOST
}
