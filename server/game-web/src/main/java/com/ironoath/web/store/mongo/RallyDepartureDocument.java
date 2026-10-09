package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;
import com.ironoath.web.social.SocialStore.RallyDeparturePlan;

/** 职责：跨集结、行军、到期队列的出发恢复日志；没有 TTL，未消费工作不会静默过期。 */
public record RallyDepartureDocument(@Id String rallyId, RallyDeparturePlan plan) {
    public static final String COLLECTION = "social_rally_departure";
}
