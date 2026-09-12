// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 单个建筑的客户端视图。finishAt 是服务端时间戳，客户端用 TimeSync 换算成本地倒计时后每秒本地刷新，不再请求服务端（B03 §4）。
 */
public record BuildingView(
        String id,   // 建筑实例 id（玩家城内唯一）
        String configId,   // 配置表 building.json 的行 id
        int level,
        int gridX,
        int gridY,
        BuildingStatus status,
        Long finishAt,   // 升级完成时刻；非升级中为 null
        Long remainingSeconds,   // 剩余秒数，服务端算好后下发，客户端不得自行推算负数
        long progress,   // 进度（定点 0~10000），响应时刻的快照。客户端用 startedAt/totalSeconds 在本地把它推进起来（见下），本字段同时作为 totalSeconds=0 时的回退值
        long startedAt,   // 升级开始时刻（服务端时间戳）；非升级中为 0。与 totalSeconds 一起让客户端能在本地复刻服务端的进度公式 —— 否则进度是响应快照、倒计时在本地走，两者会越差越远
        long totalSeconds,   // 本次升级的当前总时长（秒，加速会把 finishAt 与它一起压缩）；非升级中为 0。进度 = (服务端当前时刻 - startedAt) / (totalSeconds × 1000)
        int helpCount)   // 已获得的联盟/小队帮助次数
{
}
