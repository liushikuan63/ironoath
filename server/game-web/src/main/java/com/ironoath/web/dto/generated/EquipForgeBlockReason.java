// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 为什么这一件现在不能强化。`NONE` = 没拦着（此时 `canForge=true`），其余两种都是真会发生的：
 * `MAX_LEVEL` 已到 `forgeMax`（此时 `nextCostIron=0`，别拿 0 当免费），`IRON_LOW` 铁不够。
 * 与科技那族同一个形状的理由也相同：**判定只在服务端一处**，`canForge` 与 `blockReason` 是同一次计算的两种读法
 * （布尔给按钮、枚举给提示文案）。客户端不许自己拿 `forgeLevel` 与 `forgeMax` 比一遍 —— 两份判定的分叉不报错，
 * 症状是「按钮亮着却按失败」。
 * 不声明「实例不存在」：那是参数错误，直接回错误码，不会出现在列表视图里。
 */
public enum EquipForgeBlockReason {
    NONE,
    MAX_LEVEL,
    IRON_LOW
}
