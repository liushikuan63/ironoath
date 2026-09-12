package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;

import com.ironoath.core.social.Rally;

/**
 * 职责：集结的 MongoDB 文档模型。
 * 依赖：{@link Rally} 的公开状态。
 *
 * <p><b>participants 用数组保存顺序</b>：谁先响应在集结里是有意义的信息，
 * 而且发起人的兵力就在这张表里 —— 丢一项就是出发时少一支队伍。
 *
 * <p><b>status 与 scope 存枚举名</b>：ordinal 会在枚举调序时把"已出发"读成别的状态，
 * 而这类错误只在重启后出现，测试很难覆盖。
 */
public record RallyDocument(
        @Id String rallyId,
        String scope,
        String groupId,
        String initiatorId,
        int maxMembers,
        int minMembers,
        long createdAt,
        long prepareUntil,
        List<ParticipantEntry> participants,
        String status,
        DepartureEntry departure,
        long targetX,
        long targetY,
        String targetType) {

    public static final String COLLECTION = "social_rally";

    record TroopEntry(String unitId, long count) {
    }

    record ParticipantEntry(String playerId, List<TroopEntry> troops, List<String> heroes) {
    }

    record DepartureEntry(List<TroopEntry> mergedTroops, long totalTroops, int memberCount, long departAt) {
    }

    static RallyDocument fromDomain(Rally rally) {
        List<ParticipantEntry> participants = new ArrayList<>();
        for (Map.Entry<String, Rally.Participant> entry : rally.participants().entrySet()) {
            List<TroopEntry> troops = new ArrayList<>();
            for (Map.Entry<String, Long> troop : entry.getValue().troops().entrySet()) {
                troops.add(new TroopEntry(troop.getKey(), troop.getValue()));
            }
            participants.add(new ParticipantEntry(entry.getKey(), troops,
                    List.copyOf(entry.getValue().heroes())));
        }
        Rally.Departure departure = rally.departure();
        DepartureEntry departureEntry = null;
        if (departure != null) {
            List<TroopEntry> merged = new ArrayList<>();
            for (Map.Entry<String, Long> troop : departure.mergedTroops().entrySet()) {
                merged.add(new TroopEntry(troop.getKey(), troop.getValue()));
            }
            departureEntry = new DepartureEntry(merged, departure.totalTroops(),
                    departure.memberCount(), departure.departAt());
        }
        return new RallyDocument(rally.rallyId(), rally.scope().name(), rally.groupId(),
                rally.initiatorId(), rally.maxMembers(), rally.minMembersRequired(), rally.createdAt(),
                rally.prepareUntil(), participants, rally.status().name(), departureEntry,
                rally.targetX(), rally.targetY(), rally.targetType());
    }

    Rally toDomain() {
        Map<String, Rally.Participant> parts = new LinkedHashMap<>();
        for (ParticipantEntry participant : participants) {
            Map<String, Long> troops = new LinkedHashMap<>();
            for (TroopEntry troop : participant.troops()) {
                troops.put(troop.unitId(), troop.count());
            }
            parts.put(participant.playerId(), new Rally.Participant(participant.playerId(), troops,
                    participant.heroes()));
        }
        Rally.Departure departureDomain = null;
        if (departure != null) {
            Map<String, Long> merged = new LinkedHashMap<>();
            for (TroopEntry troop : departure.mergedTroops()) {
                merged.put(troop.unitId(), troop.count());
            }
            departureDomain = new Rally.Departure(merged, departure.totalTroops(),
                    departure.memberCount(), departure.departAt());
        }
        return Rally.restore(rallyId, Rally.Scope.valueOf(scope), groupId, initiatorId, maxMembers,
                minMembers, createdAt, prepareUntil, parts, Rally.Status.valueOf(status),
                departureDomain, targetX, targetY, targetType);
    }
}