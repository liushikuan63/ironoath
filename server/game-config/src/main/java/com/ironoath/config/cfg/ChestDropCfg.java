// 由 tools/config-gen 依据 contract/config/chest_drop.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 chest_drop 的一行。
 * 宝箱掉落表。一行 = 一条掉落项：权重 weight、产出 rewardType/rewardId、单次数量 count、是否算稀有 rare。同 chestId 的所有行构成一个掉落组，按权重抽取。 【2026-09-12 同步：rewardType 枚举补 HERO】bag 协议新增整卡武将奖励类型后，本表的 ENUM 声明必须同步（ContractEnumParityTest 三处一致），否则「配置里能写、下发时翻译不出来」。**没有新增任何行**：宝箱产出不变，只是这张表从此能表达整卡。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/chest_drop.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ChestDropCfg(
        String id,   // 主键
        String chestId,   // 外键，指向 chest 表的 id
        RewardType rewardType,   // 枚举，取值见 ChestDropRewardType
        String rewardId,
        long weight,
        long count,
        boolean rare)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum RewardType {
        RESOURCE,
        ITEM,
        HERO_FRAGMENT,
        HERO,
        STAMINA,
        PRIVILEGE
    }

}
