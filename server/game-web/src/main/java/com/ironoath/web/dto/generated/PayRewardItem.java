// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一项已发放的奖励。type 是裸字符串（取值同 bag 协议的 RewardType），理由见本文件 description 末尾的取舍说明。
 */
public record PayRewardItem(
        String type,   // 奖励类别：RESOURCE / ITEM / HERO / HERO_FRAGMENT / STAMINA 等，取值与 bag 协议的 RewardType 一致（由 parity 测试钉住）。
        String id,   // 类别内的具体 id（资源 id / item 表行 id / 武将 id）。
        long count)   // 数量。
{
}
