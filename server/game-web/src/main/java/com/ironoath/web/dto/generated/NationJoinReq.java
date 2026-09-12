// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /nation/join 请求体：一个联盟整体加入国家（B13 §二「由盟主发起，全联盟加入」）。
 *
 * **发起人的身份是结构性判定，不是权限位**：role_permission 表里没有 JOIN_NATION 这一位，而且「代表全盟选择国籍」的权限来源是「你是这个盟的盟主」而不是「你在国家里担任某个官职」—— 入籍那一刻他还不在该国，任何国家侧官职都无从谈起。所以服务端按联盟 leaderId 判定，不去查一张没有这一位的表。哪天真要把它做成可配置的（例如允许干部代为申请），那是**配置表的一次口径变更**，要加行而不是改代码。
 *
 * **目标国家由调用方指名，能不能加入全部由服务端判**：入籍冷却（B13 验收 2）、该国联盟名额（memberCap 推导的上限）、是否已属于另一个国家 —— 协议里不给任何「我已满足条件」的字段，那等于让客户端替服务端做决定。
 */
public record NationJoinReq(
        String requestId,   // 幂等键。入籍是一次跨两个聚合的写操作（改国家成员表、还要把该盟成员显示为该国公民），重放一次会在审计日志里留下两条「加入」，而冷却期与名额的判定都可能被这两条之间的一次退出国绕开。
        String nationId)   // 要加入的国家 id。
{
}
