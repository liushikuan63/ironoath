// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * GET /ops/crash/dashboard 响应：按客户端版本分组的崩溃率（只读，需运维令牌）。
 *
 * **存在的理由**：崩溃上报的写侧早就通了，读侧一直只有单测在读 ——
 * OpsAppService.crashOf() 与 store 的 findCrash()/crashCount() 在生产代码里零调用点，
 * 于是 B16 验收 9「后台能收到完整堆栈 + traceId」实际只成立了一半：收得到，取不出。
 * 一个只有测试能读的方法与一个没有调用点的方法是同一族问题（收口清单 #134 那条）。
 */
public record CrashDashboardResp(
        int windowSeconds,   // 实际生效的统计窗口（秒）。**不传 windowSeconds 就是查满保留期** —— 默认值刻意不写在调用方，否则「看板查多久」会有两份真相。给了数值才会被夹进 [60, maxWindowSeconds]，所以回显是必要的：不回显的话运维以为自己查的是 30 天，而实际上服务端只算了 60 秒。
        int maxWindowSeconds,   // 窗口能拉到的上限，由 global.DASHBOARD_RETENTION_DAYS 的最大值换算 —— **不是我另定的数**：比保留期更早的数据已经被 TrackFlusher 清掉了，给一个更大的窗口只会算出一个「零崩溃」的假结论，而那正是清库动作最坏的症状。
        List<CrashVersionRow> rows)   // 每个出现过的客户端版本一行，按崩溃数倒序、同数按版本号字典序。有崩溃但没有任何 startup 上报的版本照样成行（crashRate 为 null）—— 那种组合本身就是信息：客户端在发出 startup 之前就崩了。
{
}
