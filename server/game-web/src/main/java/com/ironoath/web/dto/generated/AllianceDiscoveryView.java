// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 可申请联盟列表的一行（B26 S6）。**只下发结论字段**：满不满、我有没有申请过、上限是多少都由服务端算 —— 客户端自己拿 memberCount 与 effectiveMemberCap 比会漏掉「队长临时提过的上限」这类只有服务端知道的口径。
 */
public record AllianceDiscoveryView(
        String id,   // 联盟 id，申请时原样带回
        String name,   // 联盟名（建盟时填的那个）
        String tag,   // 标签（显示在昵称后那几个字）
        int level,   // 联盟等级
        int memberCount,   // 当前人数
        int memberCap,   // 当前人数上限（含盟主扩容后的值，不是等级表默认值）
        boolean full,   // 是否已满：服务端算的，客户端不再自己比
        boolean applied)   // 我已经申请过这个联盟（服务端的应用账本）。没有这一项，界面就只能让玩家再吃一条「申请已提交，等待审核」
{
}
