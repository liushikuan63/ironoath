// 由 tools/config-gen 依据 contract/config/mapmonster.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 mapmonster 的一行。
 * 野外怪物表，等级 1~50（B02 要求）。每行一个等级。战力与掉落按 1.25^(level-1) 递增（curve.CHAPTER_DIFFICULTY）。**与关卡共用「难度单位」（敌方总兵力）但不共用「曲线」**：野怪是开放世界的阶梯，玩家按自己的节奏接近（打不过 LV30 就去打 LV20，没有任何要求说首日必须能打野怪 LV30）；关卡是有首日底线的线性推进，前三章必须让零氪新玩家第一天走完（B09 §二），所以关卡前 30 关走 1.12 的缓坡。两者终点仍然对齐：LV50 野怪 560,520 兵 ≈ 第 50 关。原先这里写的是「共用同一条难度标尺」，那句话把两个需求不同的系统强行绑在一起，正是「第三章成为一堵墙」这个矛盾的来源。兵种构成、阶级、体力消耗随等级分段演化，分段理由写在每行 compositionNote 里。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/mapmonster.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record MapmonsterCfg(
        String id,   // 主键
        String name,
        long level,
        long unitTier,
        long infantryCount,
        long cavalryCount,
        long archerCount,
        long siegeCount,
        long power,
        long staminaCost,
        long dropWood,
        long dropStone,
        long dropIron,
        long dropGrain,
        long dropGold,
        String compositionNote)
{
}
