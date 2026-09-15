// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 回放的时间参数。**为什么由服务端下发而不是客户端写死**：这两个数住在 `global` 表里
 * （`BATTLE_ROUND_DISPLAY_MS` / `BATTLE_PLAYBACK_SPEEDS`），客户端再写一份就是同一个事实两个家 ——
 * 表现层参数也是参数：调一次「一回合演多久」不该发版，与埋点攒批策略随版本检查下发是同一条理由。
 * **只下发表里真有的那两条**：开场/结算/技能三段时长表里没有，客户端按「与回合时长同量级」推导
 * （`BattleReportPanel.playbackOptionsOf`），在这里再造三个数就是发明。
 */
public record BattlePlaybackParams(
        long roundMs,   // 一回合演多少毫秒。来源 `global.BATTLE_ROUND_DISPLAY_MS`。
        String speeds)   // 允许的倍速档位，原样下发表里的字符串（形如 "1,2"）。解析在客户端 `BattlePlayback.parseSpeeds`，**只有一处解析**：服务端解析完再拼成数组会把规则复制两份。
{
}
