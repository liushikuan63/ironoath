// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /bot/tick 请求体。
 *
 * <b>requestId 在这里只是追踪号，不是幂等键</b> —— 与 /season/settle 恰好相反：结算是「发钱」，重复调用必须只发一次；而 tick 是<b>泵</b>，每调一次就该把到点的待办往前推一轮，把它幂等掉等于让世界上所有 Bot 永远不动。所以本端点<b>刻意不占幂等键</b> —— 谁顺手加一次 tryAcquire，症状就是「第二次开始没有反应，而日志一切正常」。
 */
public record AgentTickReq(
        String requestId)   // 调用方（外部调度系统或巡检脚本）自己生成的追踪号，会进日志。服务端不校验它是否重复。
{
}
