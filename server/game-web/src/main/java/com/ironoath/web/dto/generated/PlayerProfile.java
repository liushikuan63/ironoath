// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 玩家档案（展示用，不含任何数值判定）
 */
public record PlayerProfile(
        String playerId,   // 玩家唯一 id（服务端生成，雪花或 UUID）
        String nickName,
        int avatarId,   // 头像 id，指向配置表 avatar（B12 交付）
        long createdAt,   // 创角时间（服务端毫秒时间戳）
        long lastLoginAt)   // 最近登录时间（服务端毫秒时间戳）
{
}
