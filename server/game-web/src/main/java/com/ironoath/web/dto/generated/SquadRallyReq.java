// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /squad/rally 请求体（B10 §二）。
 */
public record SquadRallyReq(
        String requestId,   // 幂等键
        SocialCoord targetCoord,   // 集结目标坐标
        SocialTargetType targetType,   // 目标类型
        List<RallyTroop> troops,   // 发起人承诺出征的兵力（按 unitId → 数量，与行军同一口径）。**必填，且不得为空**：Rally.initiate 需要发起人的兵力才能建出第一个 Participant，而发起人一旦成为参与者就不能再 join 自己的集结（domain 会以「重复加入会让同一个人的兵被算两遍」拒绝），所以发起人的兵只有这一个入口。缺了这个字段的话，一次集结永远只能带着别人的兵出发。承诺即锁定：这些兵会当场从城内军队扣除，退出或集结取消时原路退回。
        List<String> heroes)   // 发起人随军的武将 id，可为空。合计受 global.LINEUP_HERO_COUNT 约束（整支集结共用这些位，见 RallyHeroSlotView）。
{
}
