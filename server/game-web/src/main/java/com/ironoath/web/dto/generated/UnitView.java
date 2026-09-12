// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一个兵种的完整视图：现有兵力、伤兵、训练中、是否已解锁。未解锁的也要下发（带 unlockHint），否则玩家看不到「再升 3 级兵营就能训 T3」这条最重要的养成牵引。
 */
public record UnitView(
        String unitId,
        String name,
        UnitType type,
        int tier,
        long count,   // 现有可用兵力（不含训练中、不含伤兵）
        long wounded,   // 该兵种在医院的伤兵数
        long training,   // 该兵种训练中的数量；0 表示没有在训
        Long finishAt,   // 训练完成时刻；未在训练时为 null
        Long remainingSeconds,   // 训练剩余秒数。<b>客户端本地每秒递减，不要轮询服务端</b>（B03 §4 的同一口径）
        boolean unlocked,   // 该阶级是否已解锁（由 unit 表的 unlockBuilding + unlockBuildingLevel 与城建状态共同决定）
        String unlockHint,   // 未解锁时的结构化提示，如「需要兵营 10 级，当前 6 级」。已解锁时为 null
        long trainTimeSec,   // 单个兵的训练秒数，来自 unit 表。下发给客户端是为了让「训 1000 个要多久」这个预估不必客户端自己乘
        List<ResourceAmount> trainCost)   // 单个兵的训练消耗
{
}
