// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 创建小队/联盟之前玩家要知道的那几件事（B26 S2）。**门槛与消耗全部由服务端算**：解锁要的主城等级与开服天数写在 squad_config/alliance_config 里，客户端抄一份就是第二真相 —— 表一改，界面会写着「还差 2 级」而服务端其实已经放行（反过来也一样）。
 */
public record SocialCreatePolicy(
        boolean canCreate,   // 现在能不能创建。为 false 时 reason 一定带着给人看的那句原因
        long costGold,   // 创建消耗的金币（global.ALLIANCE_CREATE_COST_GOLD；小队创建不要钱，回 0）。下发数额而不是让客户端读表：定价权在服务端
        String reason)   // 不能创建时给人看的说法，与写路径抛出去的那条是**同一份字符串**（判定只写一遍）：「需要主城 5 级，当前 1 级」/「你已经在联盟「铁誓」里」/「还需等待 86400 秒」。能创建时不下发这个字段
{
}
