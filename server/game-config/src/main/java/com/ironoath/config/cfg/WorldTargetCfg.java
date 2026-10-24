// 由 tools/config-gen 依据 contract/config/world_target.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 world_target 的一行。
 * B13/B21 首批固定战略目标候选：王城1、四关、八要塞。提供固定地图图层、新城落点预留与只读冲突检查，不代表开放攻城；旧城不迁移、不遮盖。精确落点和防守强度均为推荐，待确认与实战验证。表版本亦修订地图显示地形；坐标或地形映射变化须递增版本刷新客户端chunk缓存。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/world_target.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record WorldTargetCfg(
        String id,   // 主键
        String name,
        Kind kind,   // 枚举，取值见 WorldTargetKind
        long x,
        long y,
        String defenderId,   // 外键，指向 mapmonster 表的 id
        long effectRadius)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Kind {
        GATE,
        CAPITAL,
        FORTRESS
    }

}
