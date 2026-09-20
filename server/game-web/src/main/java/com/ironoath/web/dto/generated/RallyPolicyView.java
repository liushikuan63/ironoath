// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 某一层级发起集结的政策（B26 S13）。存在的理由与 /social/createPolicy 同一条：联盟集结要收 maxMembers 与 prepareMinutes 两个数，而它们的上下界都在 global 表里 —— 客户端不许抄表，也不许自己挑默认值，否则滑条越界、服务端悄悄夹住，玩家以为自己设的是 30 人而实际是 4 人。
 */
public record RallyPolicyView(
        int minMembers,   // 最少参与人数（global.RALLY_MIN_SIZE）。低于它这一层根本开不起来，canStart 也会跟着变 false
        int maxMembers,   // **此刻**能设的最大参与人数：取「配置上限」与「我现在这个组织的实际人数」两者的小值。写口夹的就是这个式子，读口若给配置原值，滑条就会显示一个必然被夹掉的上限
        int minPrepareMinutes,   // 最短准备时长（global.RALLY_PREPARE_MIN_SECONDS 换算成分钟）
        int maxPrepareMinutes,   // 最长准备时长（global.RALLY_PREPARE_MAX_SECONDS 换算成分钟）
        int defaultPrepareMinutes,   // 滑条的起始值。**服务端给而不是客户端挑**：取的是最长那一档，理由是集结成败取决于等人，而配置的最大窗口就是设计者认定的「值得等」的上限
        boolean canStart,   // 此刻这个层级能不能发起：组织在不在、职位有没有 START_RALLY 那一位、人数够不够最低档，三条都在这里判
        String reason)   // 不能发起时那句人话原因（不出现权限码与字段名）；能发起时为 null
{
}
