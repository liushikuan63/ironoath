// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /quest/list 的响应。**读这个端点会顺手做一次日切/周切**（每日/每周任务跨期清零），所以它是惰性推进点而不是纯读 —— 服务端不跑定时器（B00 铁律），跨期清零必须挂在有人读的那一刻。
 */
public record QuestListResp(
        List<QuestView> quests,   // 全部任务（含未解锁与已领取的行）。列表由服务端按章节/类型稳定排序，客户端不需要再排。
        int claimableCount,   // 此刻可领的任务数。给红点/徽标用 —— 与 quests 里 claimable=true 的行数同源（同一次遍历算出来的两个值，不允许各算一遍）。
        long serverNow)   // 服务端时刻。
{
}
