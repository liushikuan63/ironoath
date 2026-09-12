// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /army/train 响应体。cost 是本次实际扣掉的资源（= 单个消耗 × count），客户端据此播扣减动画。
 */
public record TrainResp(
        String unitId,
        long count,
        long finishAt,
        long remainingSeconds,
        long reducedSeconds,   // 本次加速实际提前的秒数（会被剩余时间截断，不会出现负数）。开始训练时恒为 0。<b>必须单独下发</b>：客户端要据此播「-1小时」的飘字，而用 remainingSeconds 的前后差反推会受时钟抖动影响；/item/use 的契约也要求回「实际提前了多少」。
        List<ResourceAmount> cost,
        long troopsInUse,
        long troopCap,
        long serverNow)
{
}
