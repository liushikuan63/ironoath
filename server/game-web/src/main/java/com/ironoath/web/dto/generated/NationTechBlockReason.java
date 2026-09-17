// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 为什么现在不能研究这一行。`NONE` = 没拦着（此时 `canResearch=true`）。其余四种都是真会发生的：
 * `NATION_LOW` 国家等级不足（表列 `requireNationLevel`），`TREASURY_LOW` 国库余额不足，`MAX_LEVEL` 已满级，
 * `NOT_OFFICER` 操作者没有研究权限（读的是 `role_permission` 表，不在这里硬编码官职）。
 * 与个人科技那份 `TechBlockReason` **刻意不合并成一个 def**：个人那一份有 `QUEUE_BUSY`（一次一队列）而国家根本没有队列，
 * 合并会让其中一位永久无意义 —— 两个枚举各自的取值都必须能被触发，才是诚实的契约。
 */
public enum NationTechBlockReason {
    NONE,
    NATION_LOW,
    TREASURY_LOW,
    MAX_LEVEL,
    NOT_OFFICER
}
