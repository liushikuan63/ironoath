// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 国家科技的一行。与个人科技的 `TechView` 同形而**不共用 def**：国家没有研究队列与剩余时间，
 * 共用会把 `researching` / `nextTimeSec` 两位变成恒零字段（"有名字零读取点"的契约版本）。
 */
public record NationTechView(
        String techId,   // `nation_tech.json` 的行 id，研究时原样回传（服务端按它查表，不认下标）。
        String name,   // 表里的中文名，客户端不硬编码。
        TechSchool school,   // 学派，界面按它分组。
        TechEffectAttr effectAttr,   // 改的是哪个数。国家这一份与个人科技的同属性**相加后作用一次**（§五④），所以这里给的是属性路标而不是一个通用百分比。
        Long effectValuePerLevelFixed,   // 每级增益（定点万分比：400 = +4%/级）。表里的 `effectValue` 原样下发，不乘当前等级 —— 合计由服务端算。
        int level,   // 当前等级。0 = 全国还没研究过这一行（国家账本里不存 0 占位，与 `PlayerTech.levelOf` / `Alliance#techLevel` 同一条读法）。
        int maxLevel,   // 等级上限（表列 `maxLevel`）。
        int requireNationLevel,   // 前置：国家要到几级（表列）。国家的当前等级在 `NationTechListView.nationLevel`，读的是 `Nation.level()`（由成员联盟数落到 `nation_config` 的三级规则），不是玩家报的数。
        long nextCostTreasury,   // 研究下一级要花多少国库。曲线 `BUILDING_COST`（比率 1.22，复用）× 表列 `costBaseTreasury`。满级时为 0。
        boolean canResearch,   // 服务端算好的"现在点研究会不会成功"（含权限位判定）。客户端不许自己按等级与国库判第二遍 —— 那是第二个家。
        NationTechBlockReason blockedReason)   // 拦着的原因；没拦着时是 `NONE`（与 `canResearch` 同一次算出）。
{
}
