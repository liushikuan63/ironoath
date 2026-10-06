// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 榜的类型（B23 §一 1）。与 `SeasonSettlement.Board` **逐一对应且同序**——刻意不复用那个枚举：那个是结算侧的领域类型，客户端不该依赖结算的内部形状；对应关系由 `RankBoardService.boardOf` 的 `valueOf(name())` 与 `RankEndpointTest` 的枚举断言钉住（漂移的表现是服务端认得的榜客户端解析不出来）。
 *
 * 五张榜里只有 `WAR` 是 2026-10-06 V18 加的（国战赛季分，玩家维度）。⚠️ **它不参与赛季结算依据**（`Rules.snapshotBoard` 取的是战力榜），也不会自动进 `ALLIANCE` / `NATION`：那两张是**结算依据榜**按成员的合计（B23 §五 裁决①「与赛季结算同源，不造第二本账」的执行形状），把国战分并进去要先拍"国家榜到底加什么分"这一问，见 `收口清单.md` 的 #756。
 */
public enum RankType {
    POWER,
    KILL,
    ALLIANCE,
    NATION,
    WAR
}
