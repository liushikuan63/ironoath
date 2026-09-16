// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 玩家对一步做了什么。`COMPLETE` 是「我做完了这一步」（客户端只有上报权，能不能推进由服务端按状态判），`SKIP` 是「跳过」（仅 `skippable=true` 的步骤允许）。
 */
public enum GuideAction {
    COMPLETE,
    SKIP
}
