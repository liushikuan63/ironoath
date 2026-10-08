// 由 tools/config-gen 依据 contract/config/level_reward.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 level_reward 的一行。
 * 等级奖励表。一行一等级（1..40，对应主城等级），每行给该级「可领取」的木/石/金币绝对值。裁决三连（2026-10-08 弹窗，收口清单 #829）：载体＝本表；发放时机＝玩家进「等级奖励」面板点「领取」才入账（不是升级到即发）；可见入口＝客户端新建的等级奖励面板。数值口径（同轮裁决，比例本身此前从未裁）：木/石 = 该级主城造价 × 段比例，段比例 1→30 取 5%、31→40 取 25%（分段而非全表统一，把奖励集中到 #591 指定的「31~40 只给资源奖励」那 10 级）；金币不按造价算（主城不吃铁粮、也没有金币造价可锚），改锚 `chapter.rewardGold` 的现读档价 200/400/600/800/1000，档位边界 = maxLevel ÷ 章节数 = 8 级一档。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/level_reward.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record LevelRewardCfg(
        String id,   // 主键
        String name,
        long level,
        long rewardWood,
        long rewardStone,
        long rewardGold)
{
}
