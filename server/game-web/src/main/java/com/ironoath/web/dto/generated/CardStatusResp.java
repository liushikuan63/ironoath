// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

/**
 * GET /pay/card 响应：月卡当前状态（B19 §一.1a）。
 *
 * **为什么要把权益也回给客户端而不是让客户端自己判断**：免广告与队列 +1 的判定住在服务端（`pay_product` 的 adFree / extraQueues），客户端只展示。客户端自己算「买过月卡 = 有权益」的话，月卡到期那一刻两端就会分叉：一边还在跳过广告，另一边已经把跳过收走了。
 */
public record CardStatusResp(
        boolean active,   // 当前是否在有效期内。**由服务端用服务器时间判定**（铁律 5）：客户端时钟可以改，改快就能多领几天。
        Long expireAt,   // 到期时刻（毫秒）。null = 从未买过。续费是在原到期时刻上叠加（B19 §五②c：未到期再买 +30 天，不设上限），所以这个值可以大于「购买时刻 + 30 天」。
        boolean claimedToday,   // 今日（UTC+8 的 DayKey）是否已领过日包。
        long claimableDays,   // 现在点领取会发出几天的日包。漏领的天数在有效期内累计补领，但**封顶为剩余有效天数**（B19 §五②a）—— 补发不会超过卡本身还剩多少天，所以「两周后回来一次领完」拿不到比日常更多的东西。0 表示今天无可领。
        boolean adFree,   // 免广告收益（仅指**跳过播放**）：`city_rule_ad_speedup_daily_limit` 的次数上限照旧生效。把次数一起免掉等于让「时间」这个核心卡点被付费彻底绕过，那是当初给广告加速设上限的理由。
        long bonusQueues,   // 月卡带来的额外建造队列格数（0 表示没有）。**这是临时权益、到期即收回**：客户端不得把它累加成永久值，服务端也不落库（每次按当前有效期推导），否则到期那一刻就出现「存档里还留着 +1」的第二真相。
        long serverNow)   // 服务端当前时刻，客户端据此画倒计时（铁律 5）。
{
}
