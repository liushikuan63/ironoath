// 由 tools/config-gen 依据 contract/config/chest.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 chest 的一行。
 * 宝箱本体表。一行 = 一个可批量开启的宝箱道具，记录它的保底阈值与单次批量上限。B04 §4「宝箱走随机（服务端 PRNG + seed），结果可复现」的配置来源。掉落内容在 chest_drop 表，按 chestId 关联。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/chest.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ChestCfg(
        String id,   // 主键
        String name,
        long pityThreshold,
        long maxBatchCount)
{
}
