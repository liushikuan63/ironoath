// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /social/permissions 响应体（B10 验收 4：权限矩阵配置化）。**下发的是结论而不是矩阵**：客户端拿到「我能做什么」的列表就能决定按钮灰不灰，不需要知道 role_permission 表长什么样 —— 把表下发出去等于把权限模型暴露给客户端，而客户端的任何判断都可以被绕过。
 */
public record PermissionListResp(
        String scope,   // 权限所属层级
        String role,   // 我在该层级的职位
        List<String> permissions,   // 我在该层级拥有的权限码（role_permission.permission）
        long serverNow)   // 服务端时间戳
{
}
