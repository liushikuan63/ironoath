// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 单一资源的惰性结算快照。current 为已结算值，客户端展示时需按 (serverNow - lastSettle) * perHour / 3600 做本地预测，服务端结果到达后强制纠偏。
 */
public record ResourceState(
        long current,   // 已结算的资源量
        long cap,   // 仓库容量上限
        long protectedAmount,   // 受保护不可掠夺量（B04 由仓库/护盾决定，B01 恒为配置初始值）
        long perHour,   // 每小时产量（B01 取配置底产，B03 起由建筑聚合）
        long lastSettle)   // 上次惰性结算的服务端时间戳（毫秒）
{
}
