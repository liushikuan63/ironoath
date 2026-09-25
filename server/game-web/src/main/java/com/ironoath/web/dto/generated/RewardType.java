// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 奖励类型枚举。取值必须与 com.ironoath.core.reward.RewardType 一一对应（由 ContractEnumParityTest 的 rewardTypeMatchesCore 断言，两边漂移会让服务端下发的字符串在客户端解析不出来）。没有对应的配置表，因此不能用 x-enum-source 自动校验。HERO 是整卡武将（写进武将册），HERO_FRAGMENT 是碎片（进背包），两者下游不同，不可互替。
 */
public enum RewardType {
    RESOURCE,
    ITEM,
    HERO_FRAGMENT,
    HERO,
    STAMINA,
    PRIVILEGE
}
