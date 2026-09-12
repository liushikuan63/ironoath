// 由 tools/config-gen 依据 contract/config/chapter.json（表 version=4） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 chapter 的一行。
 * 章节表：5 章、每章 10 关（B02 要求每章 10 关）。逐关数据在 stage 表，本表只放章级信息。
 *
 * **difficultyBase 是推导值不是手写值** = 本章第 1 关的敌方总兵力，由 global 表的 STAGE_DIFFICULTY_BASE / RATIO_EARLY / EARLY_THROUGH / RATIO_LATE 四个参数决定。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/chapter.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ChapterCfg(
        String id,   // 主键
        String name,
        long chapterNo,
        long stageCount,
        long requireMainLevel,
        long difficultyBase,
        String preChapter,   // 外键，指向 chapter 表的 id
        long rewardGold,
        long rewardHeroFragment,
        long rewardStamina)
{
}
