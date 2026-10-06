// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一个参战方的积分行。三类积分**分开给而不是只给合计**（B13 验收 6 要「三类积分计算正确」可分别核对，只给合计的那份视图无法复核）。
 */
public record WarNationScoreView(
        String nationId,   // 参战国家 id，客户端据此定位那一行。**不得直接上屏**（B13 红线：内部 id 不印给玩家）—— 上屏的是 `nationName`。
        String nationName,   // 国名，服务端下发（同 `NationRelationView.nationName` 那条）。**不在 `required` 里**：一个国家可能在战争进行中被解散，那时查不到名字。查不到时这里是 null，客户端给「未知国家」这类回退语（同 `GachaHistory` 的 `未知武将` 那一条），**绝不许回落到裸 id** —— 名字要与战报、聊天、客服工单里的称呼一致，所以客户端也不许自己拼。
        long occupyScore,   // 占领王城的时长积分（每分钟 `WAR_SCORE_OCCUPY_PER_MINUTE`）。
        long killScore,   // 击杀积分（每个单位 `WAR_SCORE_KILL_PER_UNIT`）。
        long buildingScore,   // 占领建筑积分（每次 `WAR_SCORE_BUILDING_PER_CAPTURE`）。
        long totalScore,   // 三项之和，名次按它排。**平分时不给胜者**：两国同分意味着谁都没赢，按 id 字典序硬挑一个会让玩家觉得结果是被系统指定的。所以这里不出现「第几名」，排名由客户端按这一列显示顺序呈现。
        int gatesHeld,   // 该国当前持有的关卡座数。上限是 `WarStatusResp.gateCount`。
        boolean attackQualified)   // 是否已取得进攻资格（持有至少一座关卡）。**由服务端判**，客户端不许自己按 gatesHeld>0 再算一遍 —— 判定写两处就会有第二处不与领域层同步的那天。
{
}
