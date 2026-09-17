// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 为什么现在不能研究这一行。`NONE` = 没有拦着的（此时 `canResearch=true`），其余四种都是真会发生的情况：
 * `ACADEMY_LOW` 学院等级不够（`requireAcademyLevel`），`QUEUE_BUSY` 队列被占（一次一队列），
 * `RESOURCE_LOW` 资源不够，`MAX_LEVEL` 已满级。
 * `canResearch` 与这一位是**同一次计算的两种读法**（布尔给按钮，枚举给提示文案），都由服务端一处产出，
 * 所以不存在两个家分叉的问题 —— 客户端不许自己按等级与资源再判一遍（那才是第二个家）。
 * 不声明「科技不存在」：那是请求参数错误，直接回错误码，不会出现在列表视图里。
 */
public enum TechBlockReason {
    NONE,
    ACADEMY_LOW,
    QUEUE_BUSY,
    RESOURCE_LOW,
    MAX_LEVEL
}
