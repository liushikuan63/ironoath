// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 一次脚本下发。体积受 `PERF_PAYLOAD_MAX_BYTES` 约束（验收 7）：7 步 × 一句文案远小于预算，真超了要按章节分页而不是悄悄超。
 */
public record GuideScriptResp(
        List<GuideStepView> steps,   // 全部步骤，顺序即表序（服务端不重排，客户端不许自己排 —— 两处排序迟早分叉）。
        String version,   // 脚本版本 = `guide.json` 的 version。服务端**不做条件返回**（七步的响应实测不到 1 KB，省流量是零收益，而一个不改变行为的 ?version= 参数会成为接口上的假象）：客户端自己拿它与手上那份比对，相同就不必重画；热更之后版本号会变，这就是验收 3 的另一半。
        Long nextStepIndex,   // 该玩家当前该做哪一步（**续传的那一位**：进度落服务端存档，杀进程重进回到这里而不是从头）。null 有两种：已经走完，或这个号压根不该看引导（applies=false 且从没开始过）；从没开始但该看时下发 1 —— 与 `finished` 配合，不重复发奖也不重复打扰。
        boolean applies,   // 这个账号该不该走引导（B18 §五③：只对新号）。false 时 `steps` 仍会给出（客户端不必分两套代码），但界面不显示引导。
        long serverNow)   // 服务端时刻（毫秒）。引导里任何「等待/冷却」的展示都由它算，不用本地时钟（铁律 5）。
{
}
