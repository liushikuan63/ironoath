// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /alliance/sync 响应体。**changed/removed 只含变化项**：version 相同且无变化时两个列表都为空（对应 B07 验收 6 的同一条纪律：无变化时二次请求的数据量为 0）。
 */
public record AllianceSyncResp(
        long version,   // 本次同步后的版本号
        boolean unchanged,   // 客户端版本已是最新。true 时下面所有列表都为空，客户端直接复用缓存
        List<AllianceMember> changedMembers,   // 新增或变化的成员
        List<String> removedMemberIds,   // 已离开的成员 id
        long fund,   // 联盟资金（标量小，每次都给，省得客户端自己累加 diff）
        int level,   // 联盟等级
        int memberCount,   // 成员数
        String announcement,   // 联盟公告
        long serverNow)   // 服务端时间戳
{
}
