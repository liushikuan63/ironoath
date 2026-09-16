// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一行活动对当前玩家、当前窗口的状态。三个取值各自对应一条服务端判定，客户端只显示不判断。
 */
public enum ActivityState {
    RUNNING,
    CLAIMABLE,
    EXPIRED
}
