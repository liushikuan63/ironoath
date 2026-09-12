// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 赛季段位（B14 §2）。取值与 game-core 的 {@code SeasonTier.Tier} 逐一对应，名字与<b>顺序</b>都由 {@code NationPayEnumParityTest} 断言 —— 段位是玩家互相报的称呼，两侧名字一旦漂移，面板就会把王者显示成未知；而顺序漂移更阴，因为「哪一档更高」是按 ordinal 比的。
 *
 * <b>按 matchPower 而不是 displayPower 划分</b>：后者含峰值记忆与全部已拥有武将，用它分段的后果是「卸兵压分反而掉段」，把 B08 明确要堵的行为变成了收益。
 */
public enum SeasonTier {
    BRONZE,
    SILVER,
    GOLD,
    PLATINUM,
    DIAMOND,
    KING
}
