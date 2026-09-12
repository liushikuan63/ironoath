// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 解散国家的响应。**同样不回国家视图**（理由与 {@code NationLeaveResp} 一致，而且这次对象是真的不存在了）；一次亡国操作最需要留下的是审计：哪个国、叫什么、解散时带着几个成员联盟与多少钱。
 */
public record NationDisbandResp(
        String nationId,   // 被解散的国家 id。记录留在库里供审计（{@code Snapshot.disbandedAt} 非 0），但它不再是一个可查询、可外交、可入籍的对象，也不再占用本服的国家名额。
        String nationName,   // 国名，服务端下发（要与战报、聊天、客服工单里的称呼一致）。
        int memberAllianceCount,   // 解散那一刻的成员联盟数。这些联盟每个都进入入籍冷却（与主动退出、被开除同一条规则），所以这个数就是「本次操作影响到多少个联盟」。
        long treasuryWrittenOff,   // 被核销的国库余额。**必须回给调用方并留在国库日志里**：国库是公共资产，一笔静默消失的钱正是「盟主卷款」最容易被写成实现细节的形状（B13 §3 与禁止项「不要让国库支出无日志」）。
        long serverNow)   // 服务端时间戳，即解散时刻。冷却与那笔核销日志的 at 都以此为基准。
{
}
