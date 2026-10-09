package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;
import com.ironoath.web.social.SocialStore.RallySettlement;

/** 国家集结退款的恢复日志；没有 TTL，未结清兵力不能随时间丢掉。 */
public record RallySettlementDocument(@Id String settlementId, String groupId,
                                      RallySettlement settlement) {
    public static final String COLLECTION = "social_rally_settlement";
}
