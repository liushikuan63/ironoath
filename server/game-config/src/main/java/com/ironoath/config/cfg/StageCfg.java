// 由 tools/config-gen 依据 contract/config/stage.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 stage 的一行。
 * 章节关卡表：5 章 × 10 关 = 50 行（B09 §4）。本表由 chapter/global 表的难度口径推导生成，逐关数值不要手改 —— 要改请改 global 表的四个 STAGE_DIFFICULTY_* 参数，否则曲线会被改出无法解释的突起，而「零氪首日可通前三章」这条底线会静默失效。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/stage.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record StageCfg(
        String id,   // 主键
        String chapterId,   // 外键，指向 chapter 表的 id
        long stageNo,
        String name,
        long enemyTier,
        long enemyInfantry,
        long enemyCavalry,
        long enemyArcher,
        long enemySiege,
        long staminaCost,
        boolean checkpoint,
        BossMechanic bossMechanic,   // 枚举，取值见 StageBossMechanic
        long roundLimit,
        UnitRestriction unitRestriction,   // 枚举，取值见 StageUnitRestriction
        long rewardGold)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum BossMechanic {
        NONE,
        REINFORCEMENT,
        SHIELD_PHASE,
        COUNTER_STRIKE
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum UnitRestriction {
        NONE,
        NO_SIEGE,
        CAVALRY_ONLY,
        RANGED_ONLY
    }

}
