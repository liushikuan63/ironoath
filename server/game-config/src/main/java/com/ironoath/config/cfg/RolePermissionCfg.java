// 由 tools/config-gen 依据 contract/config/role_permission.json（表 version=2） 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.config.cfg;

/**
 * 配置表 role_permission 的一行。
 * 角色权限矩阵（B10 / B13 依赖）。一行 = 一个（组织范围, 权限位）。三个角色层级 Leader / Officer / Member 在 squad / alliance / nation 三个范围内语义一致。allow* 字段用 BOOL。
 *
 * <p>本类型由生成器产出，<b>禁止手改</b>：改 {@code contract/config/role_permission.json} 的 fieldTypes 后运行 {@code npm run gen}。
 */
public record RolePermissionCfg(
        String id,   // 主键
        Scope scope,   // 枚举，取值见 RolePermissionScope
        String permission,   // 主键型标识符，只含字母数字下划线
        String permissionName,
        boolean allowLeader,
        boolean allowOfficer,
        boolean allowMember)
{
    /** 枚举取值与配置表 fieldTypes 中的 ENUM 声明完全一致（CI 校验）。 */
    public enum Scope {
        SQUAD,
        ALLIANCE,
        NATION
    }

}
