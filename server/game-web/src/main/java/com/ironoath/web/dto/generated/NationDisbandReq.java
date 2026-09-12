// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/disband 请求体：解散国家（B13 §1；领域层 {@code Nation.disband} 早就写完了，缺的只是入口）。
 *
 * **发起权来自「你是这个国的国王」，不是来自成员关系**：领域层判的就是 operator 等于 kingId，所以服务层按 kingId 找国，而不是按「他此刻还在不在某个成员联盟里」找 —— 后者会在国王所在联盟先退出国之后，悄悄把规则改成「国王亡不了自己的国」。
 *
 * 协议里没有任何「我已获授权」的字段：那等于让客户端替服务端做决定。
 */
public record NationDisbandReq(
        String requestId)   // 幂等键。这是一次不可逆、且会连带几百人国籍与一笔公共资产的写操作，重放不该产生两次核销日志。
{
}
