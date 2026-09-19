// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /army/list 响应体。这个「读」接口有副作用：它会顺带收割到点的训练与治疗（惰性结算，服务端不跑定时器），并在自动续训/补兵开着时排下一批（同样真扣资源、真占队列）。
 */
public record ArmyListResp(
        List<UnitView> units,   // 全部 20 个兵种（4 类型 × 5 阶级），含未解锁的
        long troopCap,   // 带兵上限 = Σ上阵武将统帅值 × TROOP_PER_COMMAND + 科技加成（B05 §二、B06 验收 8）
        long troopsInUse,
        long trainingInUse,   // 训练中已占用的兵力。<b>它计入上限</b>，否则玩家可以先塞满队列再换低统率武将来绕过上限
        int queueSlots,
        int queueSlotsMax,
        HospitalView hospital,
        AutoTrainView autoTrain,   // 自动续训 / 补兵的当前策略。**必须在列表里下发**：这个开关的效果（排了下一批）恰好也发生在列表这个读上，玩家要能一眼看出刚才那批是自动排的、还剩几批预算、为什么停了
        long serverNow)
{
}
