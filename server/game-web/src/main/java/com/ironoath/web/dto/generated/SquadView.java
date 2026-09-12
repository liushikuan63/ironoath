// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 小队视图（B10 §二）。**isSubSquad 与 allianceId 是 B10 关键设计点 1 的落地**：玩家加入联盟后小队自动转为联盟内分队，保留小队聊天、互助与集结 —— 熟人小圈子不能被大组织稀释，这是留存的关键细节。所以本视图在玩家入盟后**仍然完整下发**，一个字段都不少。
 */
public record SquadView(
        String id,   // 小队 id
        String name,   // 小队名
        String leaderId,   // 队长玩家 id
        List<SquadMember> members,   // 成员列表，按加入时间升序（稳定顺序，客户端不重排）
        int level,   // 小队等级。决定人数上限与商店货品（squad_config 表）
        long exp,   // 当前等级内已累计的活跃度
        long expToNext,   // 升到下一级还需多少活跃度；满级为 0
        long memberCap,   // 人数上限。**服务端算好后下发**：它同时取决于小队等级与队长主城等级（5→8→10 的第二档门槛是队长主城 8 级），让客户端自己查两张表再取小，必然会出现双端不一致
        int shopLevel,   // 小队商店的货品档位。0 表示商店尚未解锁
        long squadCoin,   // 我的小队币余额（个人资产，不是小队公共资金）
        String allianceId,   // 所属联盟 id；独立小队为 null
        boolean isSubSquad,   // 是否为联盟内分队。true 时小队功能**全部保留**（验收 1）—— 这个字段存在的意义就是让客户端能明确画出「既是分队又是小队」的双重身份，而不是把小队页签藏起来
        long dailyQuestProgress,   // 今日小队任务已完成数（合计讨伐野怪数）
        long dailyQuestTarget,   // 今日小队任务目标数。来源 global.SQUAD_QUEST_DAILY_MONSTER，下发是为了让客户端能显示「7/20」而不是自己读配置
        long serverNow)   // 服务端时间戳
{
}
