// 由 tools/config-gen 依据 contract/proto/ 下的 JSON Schema 自动生成，禁止手改。
// 要改协议请改 Schema，然后运行 `npm run gen`；CI 会用 scripts/check-contract-sync.sh 校验同步性。
package com.ironoath.web.dto.generated;

import java.util.List;

/**
 * 服务器报价与当前已购快照分离的月卡视图。购买价、每日数量和资格均由服务器下发；客户端不推算付费权益。
 */
public record MonthlyCardView(
        String productId,   // 商品标识，用于请求；界面展示name。
        String name,   // 服务器商品名称。
        boolean saleable,   // 是否仍可购买；旧综合卡停售但已购买权益仍可领取。
        long priceCents,   // 购买当前报价，分。
        long durationDays,   // 本次商品每份持续天数。
        long initialGold,   // 当前报价的购买初始金币，旧权益不补发。
        List<MonthlyCardRewardView> dailyRewards,   // 当前购买报价的每日奖励。
        List<MonthlyCardRewardView> ownedDailyRewards,   // 当前有效已购订单快照的每日奖励；领取按该快照，不随新报价修改。
        boolean active,   // 是否有当前有效已购周期。
        Long expireAt,   // 同商品续购后的最后到期时刻；未买null。
        long claimableDays,   // 目前可领份数；新卡04:00业务日、已购周期总份数上限，剩余有效天数封顶。
        boolean adFree,   // 该商品当前有效权益，免播放不增加次数。
        long bonusQueues,   // 该商品当前有效额外队列数。
        boolean earlyCollectAll,   // 该商品当前有效提前收割权益；不豁免四资源手动教学。
        long nextRefreshAt,   // 新卡下一次北京时间04:00刷新；旧综合卡仍采用既有自然日。
        boolean offerAdFree,   // 当前购买报价免广告权益，和active生效权益分开。
        long offerBonusQueues,   // 当前购买报价的额外队列。
        boolean offerEarlyCollectAll)   // 当前购买报价的提前收割权益，仍须四种手动教学。
{
}
