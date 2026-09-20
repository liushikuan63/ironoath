// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /stage/challenge 响应体。
 */
public record ChallengeStageResp(
        String reportId,   // 战报 id，可用 GET /battle/report 取完整回放。战斗结果本身不在这里重复下发 —— 逐回合数据只在玩家点开回放时才需要
        StageStars stars,
        int starsEarned,   // 本次获得的星数（不是历史最好）
        boolean newBest,   // 本次是否刷新了历史最好成绩。客户端据此播放「新纪录」动效
        List<StageReward> rewards,
        List<StageLoss> losses,   // 本次损失，unitId → 数量 + 展示名。**必须逐阶级下发**：只给一个总数的话，玩家看不出自己掉的是 T1 还是 T5，而这两者的代价差一个数量级
        long staminaCost,
        long staminaCharged,   // 实扣体力。失败时为 0（B09 验收 1）—— 两个字段都下发是为了让「失败不扣体力」这条规则在客户端可见，否则玩家会以为体力被偷扣了
        StageProgressView progress,
        long serverNow)
{
}
