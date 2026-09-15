// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /ops/crash/recent 的一行：一条崩溃的索引信息，**不含堆栈**。
 *
 * 为什么不顺手把堆栈带上：堆栈按 payload 预算最长 20KB（服务端还会截断并标注原长），
 * 列表默认 20 条就是 400KB —— 一条只读端点会因此成为全仓最大的响应，
 * 而它面对的正是大面积崩溃发生时（那时最需要它，也最容易被自己打死）。
 * 所以这里给 stackChars 让运维决定要不要按 traceId 去取明细。
 */
public record CrashListItem(
        String traceId,   // 取明细的键：GET /ops/crash/detail?traceId=... 拿完整堆栈。
        String clientVersion,   // 崩溃时的客户端版本。大面积崩溃时第一件要看的事就是「是不是集中在某一个版本」。
        String message,   // 错误摘要（客户端取异常第一行）。
        String sceneName,   // 崩溃时所在场景；null 表示崩在场景切换之间（不是「没取到」，那本身是定位信息）。
        long clientTs,   // 客户端毫秒时间戳。与 serverTs 之差就是该玩家的时钟偏移。
        long serverTs,   // 服务端收到时刻。排序与窗口都按它算（铁律 5）。
        int stackChars)   // 已落库堆栈的字符数。**被服务端截断过的堆栈，截断标记与「原长 N 字符」就写在堆栈文本里**，取明细看得到；列表只给长度，够决定要不要继续查。
{
}
