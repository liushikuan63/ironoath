package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;

import com.ironoath.core.social.Alliance;
import com.ironoath.core.social.AllianceRole;

/**
 * 职责：联盟的 MongoDB 文档模型。
 * 依赖：{@link Alliance} 的公开状态。
 *
 * <p><b>name 与 tag 是唯一索引列</b>：联盟名与标签都是全局唯一的查询入口，
 * 两个联盟共用一个名字等于其中一个从名字上消失。判定仍以领域状态为准，
 * 索引只负责让"先查后写"之间的竞态也能被原子拒绝。
 *
 * <p><b>donatedToday 的 key 是 {@code playerId + ":" + dayKey} 复合键</b>，
 * 必须原样保存 —— 丢掉这份账本等于每天白送无限次捐献。
 */
public record AllianceDocument(
        @Id String allianceId,
        String name,
        String tag,
        String leaderId,
        List<MemberEntry> members,
        List<ContributionEntry> contributions,
        List<DonationEntry> donatedToday,
        List<TechEntry> techLevels,
        int level,
        long exp,
        long fund,
        int territoryCount,
        int paidCapTier,
        long version,
        long disbandedAt) {

    public static final String COLLECTION = "social_alliance";

    record MemberEntry(String playerId, String role) {
    }

    record ContributionEntry(String playerId, long amount) {
    }

    /** {@code playerDayKey} 就是领域内的复合键，不要在映射层拆开再拼回去。 */
    record DonationEntry(String playerDayKey, int count) {
    }

    record TechEntry(String techId, int level) {
    }

    static AllianceDocument fromDomain(Alliance alliance) {
        List<MemberEntry> members = new ArrayList<>();
        for (Map.Entry<String, AllianceRole> entry : alliance.members().entrySet()) {
            members.add(new MemberEntry(entry.getKey(), entry.getValue().name()));
        }
        List<ContributionEntry> contributions = new ArrayList<>();
        for (Map.Entry<String, Long> entry : alliance.contributions().entrySet()) {
            contributions.add(new ContributionEntry(entry.getKey(), entry.getValue()));
        }
        List<DonationEntry> donations = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : alliance.donatedTodayByKey().entrySet()) {
            donations.add(new DonationEntry(entry.getKey(), entry.getValue()));
        }
        List<TechEntry> techs = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : alliance.techLevels().entrySet()) {
            techs.add(new TechEntry(entry.getKey(), entry.getValue()));
        }
        return new AllianceDocument(alliance.id(), alliance.name(), alliance.tag(), alliance.leaderId(),
                members, contributions, donations, techs, alliance.level(), alliance.exp(),
                alliance.fund(), alliance.territoryCount(), alliance.paidCapTier(),
                alliance.version(), alliance.disbandedAt());
    }

    Alliance toDomain(Alliance.Rules rules) {
        Map<String, AllianceRole> memberRoles = new LinkedHashMap<>();
        for (MemberEntry member : members) {
            memberRoles.put(member.playerId(), AllianceRole.valueOf(member.role()));
        }
        Map<String, Long> contributionMap = new LinkedHashMap<>();
        for (ContributionEntry contribution : contributions) {
            contributionMap.put(contribution.playerId(), contribution.amount());
        }
        Map<String, Integer> donatedMap = new LinkedHashMap<>();
        for (DonationEntry donation : donatedToday) {
            donatedMap.put(donation.playerDayKey(), donation.count());
        }
        Map<String, Integer> techMap = new LinkedHashMap<>();
        for (TechEntry tech : techLevels) {
            techMap.put(tech.techId(), tech.level());
        }
        return Alliance.restore(allianceId, name, tag, leaderId, rules, memberRoles, contributionMap,
                donatedMap, techMap, level, exp, fund, territoryCount, paidCapTier, version, disbandedAt);
    }
}