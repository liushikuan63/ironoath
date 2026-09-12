// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个随军武将位在合并行军里的状态。
 *
 * **为什么必须把落选原因也下发**：武将位按加入顺序抢，成员点完「加入」只看到自己加成功了，如果面板不说他的武将落选以及为什么，他会以为加成生效了 —— 打输之后才发现在白送一次行军。「协商谁能上」是集结的核心社交动作，而协商需要一份看得见的事实。
 */
public enum RallyHeroSlotState {
    SELECTED,
    OVER_CAP,
    DUPLICATE
}
