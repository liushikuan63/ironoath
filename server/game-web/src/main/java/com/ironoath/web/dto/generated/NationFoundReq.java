// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/found 请求体：建国。
 *
 * 前置（B13 §1，全部由服务端校验）：主城 16 级 + 开服 D14 + 当前在某联盟中。这三条都不是客户端能替服务端决定的，所以协议里没有任何「我已满足前置」的字段。
 */
public record NationFoundReq(
        String requestId,   // 幂等键。建国是一次不可重复的写操作：重放会造出两个同名国家，而国名唯一性检查在第一次之后就通过了（第一个国家已经占用了那个名字，第二个会被拒 —— 但如果两次请求并发，检查与写入之间没有锁就会双双通过）。
        String name,   // 国名。长度与敏感词校验在服务端。
        long capitalX,   // 都城横坐标。必须是发起人联盟领地内或无主的格子 —— 具体规则由服务端按世界状态判定，客户端给的只是意愿。
        long capitalY)   // 都城纵坐标。
{
}
