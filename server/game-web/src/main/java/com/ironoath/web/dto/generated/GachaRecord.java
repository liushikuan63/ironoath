// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条抽取记录（合规要求可查）。
 */
public record GachaRecord(
        long time,   // 抽取时刻（服务端时间戳，铁律 5）。
        String poolId,   // 卡池 id。记录必须带池子：不同池子的概率不同，不带池子的记录无法用来核对公示概率。
        String heroId,   // 抽到的武将 id。
        boolean isPity)   // 这一抽是否由保底触发。**必须下发**：公示里写了保底，玩家就要能在自己的记录里看到保底确实生效过 —— 一个从未标记过保底的记录列表，等于让玩家只能相信而无法验证。
{
}
