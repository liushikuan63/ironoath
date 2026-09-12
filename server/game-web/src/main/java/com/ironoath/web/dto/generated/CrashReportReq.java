// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * POST /ops/crash 请求体（B16 §6 全局错误捕获 + 上报，验收 9：后台能收到完整堆栈 + traceId）。
 *
 * **traceId 是这个契约存在的理由**：没有它，后台收到的是一堆匿名堆栈，而线上排查的第一步永远是「这个玩家当时在做什么」。禁止项写死了「不要让日志无 traceId」。
 */
public record CrashReportReq(
        String traceId,   // 全链路追踪 id。头名在服务端是 TraceIdFilter.TRACE_HEADER 常量，客户端网络层持同一字面量 —— **它刻意不做成配置项**：客户端发第一个请求时就要用这个头，无法跟随服务端配置热更，做成配置只会多出一份会与代码漂移的副本。客户端在每次请求时透传，崩溃时把最后一条上报出来 —— 于是「玩家说卡住了」可以被还原成一条完整链路。
        String message,   // 错误摘要。取异常的第一行而不是整个 message：某些异常的 message 里嵌了完整的请求体，会把这一条上报撑到超过 payload 预算。
        String stack,   // 完整堆栈。验收 9 要求「完整」，所以不做截断 —— 但客户端必须在发送前把它压到 payload 预算内（超长时保留头部与尾部，中间省略并标注省略行数），因为丢掉的往往正是最深的那一帧。
        String clientVersion,   // 崩溃时的客户端版本。灰度期间这是最关键的一个字段：5% 灰度批次里崩溃率翻倍，只有按版本分组才能看出来，而「崩溃率上升」这个总量指标在 5% 的批次里根本不动。
        String sceneName,   // 崩溃时所在场景（world / battle / city / ...）。为 null 表示崩在场景切换之间 —— 那本身就是一种有价值的定位信息，所以用 null 而不是空串，空串会被读成「有个叫空名字的场景」。
        long ts)   // 崩溃的客户端毫秒时间戳。落库时同时记录服务端时间，两者之差就是客户端时钟偏移量 —— 那是判断「这个玩家的倒计时为什么不对」的直接证据。
{
}
