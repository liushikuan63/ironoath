// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 退出国的响应。**为什么不复用 NationResp 回一份国家视图**：操作完成后这个人已经没有国籍了，GET /nation 会直接回 NATION_NOT_FOUND，回一份「刚刚离开的那个国家的视图」会让客户端刷新到一个它再也无权查询的对象上。这一次操作唯一需要立刻显示给玩家的事实是「什么时候才能再加入」，所以只回冷却时刻。
 */
public record NationLeaveResp(
        String nationId,   // 刚离开的国家 id —— 只用于日志与提示里的称呼，不再是一个可查询的入口。
        String nationName,   // 国名，服务端下发。客户端不得自行缓存拼接：那份名字要与战报、聊天、客服工单里的称呼一致。
        long cooldownUntil,   // 该联盟可再次入籍的时刻（服务端时间戳）。**必须由服务端下发而不是客户端拿一个时长自己加**：冷却时长只有一个家（global.NATION_JOIN_COOLDOWN_HOURS，经 NationRulesAssembler 换算成毫秒），而铁律 5 禁止在展示与判定两侧各算一遍时间 —— 客户端本地钟一偏，就会出现「显示还能加入、服务端却拒绝」这种说不清的提示。
        long serverNow)   // 服务端时间戳。倒计时要有基准，这个基准与 cooldownUntil 必须同源。
{
}
