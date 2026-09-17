// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一行科技的完整状态：是什么、现在几级、下一级要什么、能不能点。客户端不需要读任何配置表。
 */
public record TechView(
        String techId,   // `tech.json` 的行 id。开始研究时原样回传（服务端按它查表，不认下标）。
        String name,   // 表里的中文名（客户端不硬编码科技名，否则改表不生效）。
        TechSchool school,   // 学派，界面按它分组。
        TechEffectAttr effectAttr,   // 改的是哪个数。
        Long effectValuePerLevelFixed,   // 每级增益（定点万分比：400 = +4%/级）。表里的 `effectValue` 原样下发，**不乘当前等级** —— 合计由服务端算并用在结算里，这里给的是「下一级会多快」的展示参数。
        int level,   // 当前等级。0 = 从没研究过（**服务端账本里不存 0 占位**，读的时候缺失即 0，与联盟科技 `Alliance#techLevel` 同一条读法）。
        int maxLevel,   // 等级上限（表列 `maxLevel`）。
        int requireAcademyLevel,   // 前置：学院建筑要到几级（表列 `requireAcademyLevel`）。学院当前等级另在 `TechListView.academyLevel`，两者一比就是界面的「学院 5 级解锁」。
        long nextTimeSec,   // 研究**下一级**要多少秒（`curve.TECH_TIME`，含建造速度类加成为 0 —— 加速归口在 B20 验收 8 那一步）。已满级时为 0。
        List<ResourceAmount> nextCost,   // 研究下一级的消耗（只列该行为正的资源，四种全零的行不存在 —— `TechCostCurveTest` 守着）。已满级时为空数组。
        boolean researching,   // 这一行是不是当前队列里的那一项。单独给一位而不是靠 `queue.techId` 推：列表与队列同源于服务端，但界面要在行上直接标记，推一遍就是把判据搬到客户端。
        boolean canResearch,   // 服务端算好的「现在点研究会不会成功」。客户端不必（也不许）自己按等级与资源判 —— 那是第二个家，两个家分叉时玩家只信界面。
        TechBlockReason blockedReason)   // 拦着的原因；没拦着时是 `NONE`（与 `canResearch=true` 同一次算出）。
{
}
