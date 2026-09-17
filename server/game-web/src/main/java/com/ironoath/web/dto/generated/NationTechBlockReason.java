// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 为什么现在不能研究这一行。`NONE` = 没拦着（此时 `canResearch=true`）。其余五种都是真会发生的：
 * `NATION_LOW` 国家等级不足（表列 `requireNationLevel`），`TREASURY_LOW` 国库余额不足，
 * `OFFICER_LIMIT` 国库存钱够、但**这个官员本周的国库额度用完了**（C16 的周限额），`MAX_LEVEL` 已满级，
 * `NOT_OFFICER` 操作者没有研究权限（读的是 `role_permission` 表，不在这里硬编码官职）。
 * `TREASURY_LOW` 与 `OFFICER_LIMIT` **刻意不合并**：合并后面板会对一个"国库存钱充足"的人说"国库不够"，
 * 那是句假话，而他该等的是下周额度刷新而不是等国库进钱 —— 与写路径那两枚错误码同一分工。
 * 与个人科技那份 `TechBlockReason` **刻意不合并成一个 def**：个人那一份有 `QUEUE_BUSY`（一次一队列）而国家根本没有队列，
 * 合并会让其中一位永久无意义 —— 两个枚举各自的取值都必须能被触发，才是诚实的契约。
 * 这五位与领域层 `Nation.TechBlock` 逐项对应、由同一个判定（`techBlock`）产出，所以面板与写路径不会分叉。
 */
public enum NationTechBlockReason {
    NONE,
    NATION_LOW,
    TREASURY_LOW,
    OFFICER_LIMIT,
    MAX_LEVEL,
    NOT_OFFICER
}
