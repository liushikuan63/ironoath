// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * POST /ops/track/batch 请求体。一次上报一批（B16 §3：10 条或 10 秒触发）。
 *
 * **不带 requestId**：见本文件 description 的约束 2。
 */
public record TrackBatchReq(
        List<TrackEvent> events)   // 本批事件，按发生顺序。攒批上限来自 global.TRACK_BATCH_MAX_SIZE（随版本检查下发给客户端，所以那一份与客户端用的是同一个数）。 **入口不做拒绝式硬上限**（2026-09-13 裁决）：服务端只按 global.TRACK_INGEST_SOFT_LIMIT_FACTOR × TRACK_BATCH_MAX_SIZE 设一道**软**上限，超了保留前面若干条、截掉多余的并计入响应的 failed，HTTP 仍是 200。为什么不是硬拒：一次战斗本身就产生十几个事件，拿攒批上限当门槛会把真实战斗事件整批丢掉 —— 丢看板数据比来噪音糟。为什么这条判断必须存在：/ops/ 是不要求身份的公开路径，它是服务端唯一的工作量上界。 （本字段原先写着「服务端按 PERF_PAYLOAD_MAX_BYTES 校验体积」，而服务端从未有过那段代码 —— 那句话描述的是一个提案，不是事实。）
{
}
