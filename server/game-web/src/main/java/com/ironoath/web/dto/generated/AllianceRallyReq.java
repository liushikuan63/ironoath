// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /rally/alliance 请求体（B10 §二；真路径同 `SquadRallyReq` 那条）。本行原先写的是 `POST /alliance/rally`，与真路径相反 —— 台账 #788。
 */
public record AllianceRallyReq(
        String requestId,   // 幂等键
        SocialCoord targetCoord,   // 集结目标坐标
        SocialTargetType targetType,   // 目标类型
        int maxMembers,   // 期望的参与人数上限。**服务端会夹到 RALLY_MAX_SIZE_ALLIANCE(20)** 而不是拒绝：发起人在滑块上很容易越界，拒绝会让他以为集结功能坏了
        int prepareMinutes,   // 准备时长（分钟）。服务端会夹到 [RALLY_PREPARE_MIN_SECONDS, RALLY_PREPARE_MAX_SECONDS] 区间
        List<RallyTroop> troops,   // 发起人承诺出征的兵力（按 unitId → 数量，与行军同一口径）。**必填，且不得为空**：Rally.initiate 需要发起人的兵力才能建出第一个 Participant，而发起人一旦成为参与者就不能再 join 自己的集结（domain 会以「重复加入会让同一个人的兵被算两遍」拒绝），所以发起人的兵只有这一个入口。缺了这个字段的话，一次集结永远只能带着别人的兵出发。承诺即锁定：这些兵会当场从城内军队扣除，退出或集结取消时原路退回。
        List<String> heroes)   // 发起人随军的武将 id，可为空。上限口径与小队集结一致。
{
}
