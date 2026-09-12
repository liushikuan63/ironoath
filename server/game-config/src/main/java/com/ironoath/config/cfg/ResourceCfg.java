// 由 tools/config-gen 依据 contract/config/resource.json（表 version=3） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 resource 的一行。
 * 资源定义表。B01 最小集：只定义 5 种资源的初始值、容量与底产。perHour 在 B03 起改为由建筑聚合计算，basePerHour 仅作为无任何产出建筑时的兜底底产。
 *
 * v3 新增 STAMINA（体力）与第三个 kind：体力是 B09 打野与关卡的消耗闸门。把它做成资源行而不是独立系统，是为了复用已经修好的惰性结算 —— 「每 X 分钟恢复 1 点」「溢出不超上限」「不用定时器给全体玩家重置」这三条 B09 要求，在资源模型里分别对应 basePerHour、cap 截断、lastSettle 时间差，全部是现成的。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/resource.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record ResourceCfg(
        String id,   // 主键
        String name,
        Kind kind,   // 枚举，取值见 ResourceKind
        long initAmount,
        long initCap,
        long basePerHour)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Kind {
        BASE,
        CURRENCY,
        STAMINA
    }

}
