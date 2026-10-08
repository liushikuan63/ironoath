// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一行奖励里的一个条目。与 bag 协议的 RewardItemView、quest 协议的 QuestReward、stage 协议的 StageReward 形状相同，但生成器只支持同文件 $ref，所以这里第四份本地定义。**四份的字段名与顺序必须一致**，由 LevelRewardContractParityTest 逐字段比 RewardItemView 钉住 —— 复制而不校验才是真正的危险：漂移的症状是服务端下发的字符串在客户端解析成 undefined，而 TS 侧不会报错，UI 只会空白。
 */
public record LevelRewardItem(
        String type,   // 奖励类型。本表目前只产出 RESOURCE 一种（木/石/金币都是资源），取值集合与 bag 协议的 RewardType 一致。
        String id,   // 资源 id（WOOD / STONE / GOLD）。它是内部标识，客户端**不得**把它印到屏上 —— 展示一律读同一条目的 name。
        long count,   // 数量。恒 >= 0：表里 LONG_NONNEG 列，0 的行在服务端就不产出条目。
        String name)   // 服务端从 resource 表解析后的展示名（木材 / 石料 / 金币）。客户端不得自行翻译，也不得回退成 id —— 「屏上不出现裸 id」这一维在运行时量具里单独钉。
{
}
