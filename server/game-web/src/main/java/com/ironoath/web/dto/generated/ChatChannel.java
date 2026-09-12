// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 聊天频道（B10 §5：世界 / 联盟 / 小队 / 私聊）。四个频道是三层社交的可见化：世界频道让陌生人能被发现，联盟与小队频道让组织内部能协同，私聊让熟人关系能维持。少任何一个都会让某一层社交失去入口。
 */
public enum ChatChannel {
    WORLD,
    ALLIANCE,
    SQUAD,
    PRIVATE
}
