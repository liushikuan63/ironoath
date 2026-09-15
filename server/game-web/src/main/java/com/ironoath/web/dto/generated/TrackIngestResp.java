// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /ops/ingest 响应：埋点入口的健康度（只读，需运维令牌）。
 *
 * **这个端点存在的理由是「计数必须有出口」**：一个只有测试在读的 AtomicLong 与一个没有调用点的方法是一回事 —— 都会长成「机制在、没人看」，而 #58 那条裁决要的是「洪水必须可见」。可见的意思不是代码里有个计数器，是有人能查得到。
 */
public record TrackIngestResp(
        int softLimitEvents,   // 当前生效的入口软上限（= TRACK_BATCH_MAX_SIZE × TRACK_INGEST_SOFT_LIMIT_FACTOR）。随配置热更变化，所以每次都要回出来，不要让运维记住一个数。
        long truncatedEvents,   // 本进程启动以来因超过软上限而被截断的事件累计条数。非零即需人看：要么有客户端的攒批策略与本服不同步，要么有人在刷这个不要求身份的端点。**做成 64 位**是因为它是一个只增不减的累计值 —— 一个 int 装不下一个跑了一年的进程。
        Integer clientDroppedBatches,   // 客户端自报的丢弃批数累计（各次上报的增量相加）。这是 B16 §四 看板里「埋点丢弃数」的客户端那一半 —— 另一半是 truncatedEvents（服务端截断）。**两个数都要看得见**：只报服务端截断时，客户端在弱网下丢掉的那部分完全没人知道。
        int pendingEvents,   // 服务端二次攒批器里尚未落库的事件数（TrackFlusher 的待发队列）。
        int flushedBatches)   // 服务端已写库的批次数。与事件数一起看才是验收 3 的「批数远小于事件数」，只看一个数说明不了什么。
{
}
