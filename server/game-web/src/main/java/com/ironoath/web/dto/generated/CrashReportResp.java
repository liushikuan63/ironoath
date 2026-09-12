// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * 崩溃上报结果。**永远返回 accepted=true 或走 HTTP 错误，不做业务失败分支**：崩溃上报的调用方是一个已经处于异常状态里的客户端，给它一个需要再处理一遍的失败语义，等于在崩溃处理里再制造一次崩溃机会。
 */
public record CrashReportResp(
        boolean accepted)   // 是否已收下。false 只在服务端自身落库失败时出现，此时客户端不重试（重试也没用），但会在下次启动时补报。
{
}
