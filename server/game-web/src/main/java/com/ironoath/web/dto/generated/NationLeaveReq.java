// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/leave 请求体：全联盟退出所属国家。
 *
 * **退出与「联盟解散」走的是同一条领域规则**（Nation.removeAlliance，expelled=false）：两者都是这个联盟不再属于该国，区别只在谁做的决定 —— 这里是盟主自己，解散时是「联盟这个实体不复存在」。B13 §二的冲突表把「被国家开除」和「主动退出」写在同一行，正因为两者的后果（全联盟失去国籍 + 24h 入籍冷却）必须一致：如果主动退出没有冷却，就可以「退出 → 立刻加入敌国」，而国战的胜负恰恰取决于双方人数。
 */
public record NationLeaveReq(
        String requestId)   // 幂等键。
{
}
