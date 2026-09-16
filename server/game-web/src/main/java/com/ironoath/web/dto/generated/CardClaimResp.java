// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 月卡日包领取结果。
 */
public record CardClaimResp(
        long claimedDays,   // 本次实际补发了几天（含今日）。为 1 就是最常见的情形；大于 1 说明前面有漏领且仍在有效期内 —— 客户端要把这个数显示出来，否则玩家的感受是「今天怎么给得特别多」，而这是策划刻意要的效果。
        List<PayRewardItem> rewards,   // 本次发出的奖励（日包内容 × claimedDays）。溢出部分由发放器转邮件，不在这个列表里重复计。
        Long expireAt,   // 领取后的到期时刻，与 GET /pay/card 同口径。
        long serverNow)   // 服务端当前时刻。
{
}
