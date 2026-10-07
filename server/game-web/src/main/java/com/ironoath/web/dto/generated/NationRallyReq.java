// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /rally/nation 请求体（V22-a，B13 §46 大将军「发起国战、调动集结」）。**字段集合与 AllianceRallyReq 完全一致，且刻意不带 scope 字段**：层级由接口路径区分 —— 请求体里带上 scope 等于让客户端自己声明「我代表哪一层」，而那是服务端按国籍与职位判定的事，不能由调用方申报。同一条口径的既有先例：发起联盟集结时请求体也不带 allianceId，组织由服务端从 playerId 反查（`SocialAppService.allianceRally` 里的 `requireAllianceOf`）。
 */
public record NationRallyReq(
        String requestId,   // 幂等键
        SocialCoord targetCoord,   // 集结目标坐标
        SocialTargetType targetType,   // 目标类型
        int maxMembers,   // 期望的参与人数上限。**服务端会夹到国家层此刻的上限**（`SocialAppService.nationRallyCap` 的折叠值 = 配置上限与本国实有人数的小值）而不是拒绝：发起人在滑块上很容易越界，拒绝会让他以为集结功能坏了。这里不写死数字 —— 上限随科技放开是 V24 的口径，界值唯一来源是 `GET /rally/policy` 的 nation 视图
        int prepareMinutes,   // 准备时长（分钟）。服务端会夹到 [RALLY_PREPARE_MIN_SECONDS, RALLY_PREPARE_MAX_SECONDS] 区间
        List<RallyTroop> troops,   // 发起人承诺出征的兵力（按 unitId → 数量，与行军同一口径）。**必填，且不得为空**：Rally.initiate 需要发起人的兵力才能建出第一个 Participant，而发起人一旦成为参与者就不能再 join 自己的集结（domain 会以「重复加入会让同一个人的兵被算两遍」拒绝），所以发起人的兵只有这一个入口。缺了这个字段的话，一次集结永远只能带着别人的兵出发。承诺即锁定：这些兵会当场从城内军队扣除，退出或集结取消时原路退回。
        List<String> heroes)   // 发起人随军的武将 id，可为空。上限口径与小队集结一致。
{
}
