// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 战报可以分享到的频道（B22 §一 2）。**刻意只有两个**：世界频道是陌生人广场，把战报贴进去等于对全服广播自己的坐标与兵力；私聊是一对一，另有直接说话那条路。分享到组织内部频道（小队/联盟）才是这件事的用途 —— 让战友看见你怎么打的。
 */
public enum ShareChannel {
    ALLIANCE,
    SQUAD
}
