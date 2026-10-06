// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * `POST /nation/war/declare` 的入参：对本国之外的某一个国家宣战，开启一场 3 小时限时的王城战（B13 §一 §7）。
 *
 * **权限走 `role_permission` 表的 `DECLARE_WAR`**（`perm_nation_declare_war`：`allowLeader=true`、`allowOfficer=false`）。⚠️ 这与 `B13:46` 官职表里那句「大将军：发起国战」不一致，**以表为准**：`role_permission` 的 v3 设计说明把「任命官职、宣战、国策」三项明确收窄到国主独有（理由是「影响 800 人且不可逆」），而判定只长在表里那一处 —— 若照官职表放开到将军档，读路径（`GET /social/permissions?scope=NATION`）与写路径就会分叉，症状是「面板上那颗键亮着、点下去被拒」。Bot 结构性地不可能担任官职（B13 §2 合规红线），所以这里没有额外的 Bot 闸门。
 *
 * **全服同时只有一场未结束的仗**（由存储层的临界区保证，见 `WarStore#insertIfNoneActive`）：已经有一场就打不进第二次，错误码 `WAR_ALREADY_ACTIVE`(13020)。写成「先查后插」的话，两个国王在同一秒各自宣战会插出两场平行账，而两份各自算各自的击杀与占领分。
 *
 * **这一格交付的是「开战」这一步，不是王城战本身**：关卡与王城今天还不是地图上的可占领实体（`WorldEntityType` 只有城/野怪/资源/行军/建筑），所以宣战后板子停在 `PREPARATION`、`beginSiege` 进不去，积分只有击杀一项会动。占领时长分、建筑分与疲劳值仍属未接（`验收矩阵.md` 的 B13 验收 6/7 继续挂 ⬜）。
 */
public record WarDeclareReq(
        String requestId,   // 幂等键。重复提交不会开第二场 —— 与国库、国策那几处同一条：一场仗的起点必须由一次动作决定，而不是由重发的次数决定。
        String targetNationId)   // 被宣战国家的 id。**不得直接上屏**（B13 红线：内部 id 不印给玩家），面板用的是响应里 `WarStatusResp.scores[].nationName` 那一份服务端下发的国名。前置按这个顺序查：本国存在 → 有宣战权限 → 不是打自己 → 目标国存在 → 外交关系允许打（`Nation.mayAttackNation`，B13 验收 12「改关系即改可打名单」走的正是这一道）。
{
}
