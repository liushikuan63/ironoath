// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 联盟职位（B10 §4 权限矩阵的 alliance scope）。四级来自 B10 §2 的「盟主/副盟主/长老/成员」。**职位到权限的映射不在这里，在 role_permission 表**（B10 禁止项：不要把权限判断硬编码在代码里）。本枚举只提供「有哪些角色」，具体能不能做某件事由配置表回答。
 */
public enum AllianceRole {
    LEADER,
    OFFICER,
    ELDER,
    MEMBER
}
