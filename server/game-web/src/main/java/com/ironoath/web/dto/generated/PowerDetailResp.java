// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /player/power 的响应：战力明细面板的全部数据（B08 §1）。
 *
 * 战力三元组直接复用 PowerSnapshot，不重复声明 —— 那三个字段总是一起出现，两处各写一份迟早会漂移。
 *
 * **额外下发 currentMatchPower 与 peakMemoryFloor，是为了让 matchPower 可被玩家自己复算。** matchPower = max(currentMatchPower, peakMemoryFloor)。只给结果的话，一个刚打完大仗、兵力折损的玩家会看到一个比部队实际战力更高的数字，而他没有任何线索知道那是峰值记忆在起作用 —— 那正是「这游戏在骗我」的观感来源。把两个输入都摊开，规则就变成可以自查的，而不是需要相信的。
 */
public record PowerDetailResp(
        PowerSnapshot power,
        long currentMatchPower,   // 当前部队 + 上阵主将的实际战力，不含峰值记忆
        long peakMemoryFloor,   // 峰值记忆托底线 = round(peakPower × PEAK_POWER_MEMORY_RATIO)。它存在的唯一目的是堵「战前卸兵压分」：卸兵之后 matchPower 不会立刻掉下去，所以压分没有收益
        PowerBreakdown breakdown,
        long serverNow)   // 服务端时间戳。客户端不得用自己的时钟推算峰值衰减
{
}
