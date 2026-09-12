// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 一条待帮助请求（红点数据源，B10 验收 6「一键帮助全部」）。**alreadyHelped 必须下发**：一键帮助要跳过我已帮过的项，否则「帮助全部」会在同一个人身上重复消耗我的每日额度，而玩家看到的是「我点了 20 次却只帮到 5 个人」。
 */
public record HelpRequestView(
        String requestId,   // 请求 id
        String fromPlayerId,   // 请求者玩家 id
        String fromPlayerName,   // 请求者昵称
        HelpTargetKind kind,   // 帮助目标类型
        String targetDesc,   // 目标描述，如「伐木场 Lv7→8」。服务端拼好下发，客户端不得自行组装 —— 组装规则一旦分散到客户端就会出现三套文案
        long remainingSeconds,   // 剩余秒数（服务端算好，**绝不为负**）
        int helpedCount,   // 已获得的帮助次数
        boolean alreadyHelped)   // 我是否已经帮过这一条
{
}
