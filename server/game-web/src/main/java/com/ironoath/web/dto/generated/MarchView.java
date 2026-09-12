// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一支行军的完整视图。<b>position 由服务端按真实时间插值算出</b>：客户端自己的 (now-startAt)/(arriveAt-startAt) 插值只是表现层平滑，权威位置以这里为准，中途收到推送立即纠偏（B07 验收 10）。
 */
public record MarchView(
        String marchId,
        Coord from,
        Coord to,
        MarchStatus status,
        TargetType targetType,
        String targetId,
        String rallyId,   // 所属集结 id；普通行军为 null。 **为什么必须由服务端下发而不是让客户端去猜**：集结合并行军的主人是发起人，在成员眼里看到的就是「我自己的兵不见了」——他必须能在行军列表里认出「我的兵在那支集结队伍里」，否则每一次参与集结都会变成一条客服工单。而 March 与 Rally 的对应关系只有服务端知道（集结在社交域、行军在行军域），让客户端拿 rallyId 去 /rally/list 里比对，等于要求它自己做一次跨域 join。 刻意做成可空而不是必填：绝大多数行军与集结无关，为它们多带一个字段是噪声。
        MarchAction action,
        long startAt,
        long arriveAt,
        Long returnStartAt,   // 返程开始时刻。<b>客户端插值返程时必须用它而不是 startAt</b>，否则召回瞬间位置会跳变
        Long returnArriveAt,
        List<MarchUnit> units,
        List<String> heroes,
        long load,
        long loadCap,
        int teamSpeed,   // 队伍速度 = 最慢兵种的速度。下发是为了让玩家看懂「为什么带了攻城器就这么慢」
        Coord position,
        long progressFixed,   // 行程进度（定点 0~10000）
        Long gatherFinishAt,   // 采集完成（采满负载）的时刻；非采集状态为 null
        long serverNow)
{
}
