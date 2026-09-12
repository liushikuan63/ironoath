// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一次集结（B10 §2 集结进攻 / §1 小队集结）。**departAt 由服务端算，客户端不参与**（B07 的同一条纪律：不要用客户端定时器决定出发）。验收 11 要求「倒计时结束时所有参与部队统一出发，兵力合并正确」—— 统一出发的实现是服务端在 departAt 那一刻把 members 的兵力合成一支部队，而不是让每个人各自出发。
 */
public record RallyView(
        String rallyId,   // 集结 id
        RallyScope scope,   // 发起层级
        String groupId,   // 发起组织 id（小队 id / 联盟 id / 国家 id）
        String initiatorId,   // 发起人玩家 id
        SocialCoord targetCoord,   // 目标坐标
        SocialTargetType targetType,   // 目标类型，决定到达后的行为
        int maxMembers,   // 参与人数上限。来源 global.RALLY_MAX_SIZE_*，服务端按 scope 取
        int joinedCount,   // 已加入的人数（不含发起人则为参与数，含发起人则为总队伍数）
        long totalTroops,   // 已承诺出征的兵力合计。**在 PREPARING 期间就要显示** —— 集结的核心决策是「这波打得过吗」，而那个判断需要看到已经凑了多少兵
        long prepareUntil,   // 准备阶段截止的服务端时间戳
        long departAt,   // 统一出发时刻（= prepareUntil，除非被取消）
        RallyStatus status,   // 状态
        List<String> members,   // 参与者，按加入时间升序
        long serverNow,   // 服务端时间戳
        List<RallyHeroSlotView> heroSlots)   // 全部随军武将位及其状态，按加入顺序（发起人最先）。长度可以大于 LINEUP_HERO_COUNT —— 落选的那些也要出现在这里，见 RallyHeroSlotState。
{
}
