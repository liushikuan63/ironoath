// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /ops/crash/detail 响应：一条崩溃的完整记录，含完整堆栈（只读，需运维令牌）。
 *
 * **这一条才是 B16 验收 9 的后半句**：「后台能收到完整堆栈 + traceId」里的「能收到」
 * 指的是取得出来。写侧一直成立，读侧此前只存在于单测里。
 */
public record CrashDetailResp(
        String traceId,   // 全链路 id，可与服务端日志按同一条链路对齐。
        String clientVersion,   // 崩溃时的客户端版本。
        String message,   // 错误摘要。
        String sceneName,   // 崩溃时所在场景；null 表示崩在场景切换之间。
        long clientTs,   // 客户端毫秒时间戳。
        long serverTs,   // 服务端收到时刻。
        String stack)   // 完整堆栈。超长时尾部带「...(服务端截断，原长 N 字符)」—— 那句话在文本里而不是单独一个布尔字段：一条被截断的堆栈在后台看起来和完整的没区别，而缺的往往正是最深的那一帧，标记必须跟着文本走。
{
}
