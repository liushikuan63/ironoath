// 由 tools/config-gen 依据 contract/config/guide.json（表 version=1） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 guide 的一行。
 * 新手引导脚本表（B18）。7 步与主线 quest_main_01~quest_main_06 逐条对齐：引导走真实主线，不另造一条链。
 * 本表是**步骤序列的唯一出处**（改脚本不改包 = 硬要求），客户端全仓不得出现任何步骤文案（验收 1 的静态检查盯的就是这个）。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/guide.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record GuideCfg(
        String id,   // 主键
        String name,
        long stepIndex,
        Trigger trigger,   // 枚举，取值见 GuideTrigger
        String panelKey,
        String highlightPath,
        String maskArea,
        String text,
        boolean skippable,
        Judge judge,   // 枚举，取值见 GuideJudge
        String judgeTarget)   // 外键，指向 quest 表的 id
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Trigger {
        PANEL_OPEN,
        STATE_REACHED
    }

    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Judge {
        QUEST_DONE,
        QUEST_CLAIMED
    }

}
