package com.ironoath.web.store.mongo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;

import com.ironoath.core.social.Squad;
import com.ironoath.core.social.SquadRole;

/**
 * 职责：小队的 MongoDB 文档模型。
 * 依赖：{@link Squad} 的公开状态。
 *
 * <p><b>规则（{@code Squad.Rules}）不入库</b>：与 {@code NationDocument} 同一条 ——
 * 规则来自配置表、是可热更的运行时输入，不是存档的一部分。重建时由调用方现取当前规则，
 * 配置改小人数上限后旧小队不会被"存档里的旧上限"挡住。
 *
 * <p><b>成员与小队币用数组而不是 Map</b>：两者都按加入/发放顺序保存，
 * Map 的序列化不保证顺序，而顺序在成员列表与余额展示里是有意义的信息。
 */
public record SquadDocument(
        @Id String squadId,
        String name,
        String leaderId,
        List<MemberEntry> members,
        int level,
        long exp,
        String allianceId,
        List<CoinEntry> squadCoins,
        long squadCoinPool,
        long dailyQuestProgress,
        long disbandedAt) {

    public static final String COLLECTION = "social_squad";

    /** 一条成员：id + 角色名。存名字而不是 ordinal，枚举调序不会静默改角色。 */
    record MemberEntry(String playerId, String role) {
    }

    /** 一条小队币余额。key 集合不保证等于成员集合（离队成员的余额可能仍在账上）。 */
    record CoinEntry(String playerId, long amount) {
    }

    static SquadDocument fromDomain(Squad squad) {
        List<MemberEntry> members = new ArrayList<>();
        for (Map.Entry<String, SquadRole> entry : squad.members().entrySet()) {
            members.add(new MemberEntry(entry.getKey(), entry.getValue().name()));
        }
        List<CoinEntry> coins = new ArrayList<>();
        for (Map.Entry<String, Long> entry : squad.squadCoins().entrySet()) {
            coins.add(new CoinEntry(entry.getKey(), entry.getValue()));
        }
        return new SquadDocument(squad.id(), squad.name(), squad.leaderId(), members, squad.level(),
                squad.exp(), squad.allianceId(), coins, squad.squadCoinPool(),
                squad.dailyQuestProgress(), squad.disbandedAt());
    }

    Squad toDomain(Squad.Rules rules) {
        Map<String, SquadRole> memberRoles = new LinkedHashMap<>();
        for (MemberEntry member : members) {
            memberRoles.put(member.playerId(), SquadRole.valueOf(member.role()));
        }
        Map<String, Long> coins = new LinkedHashMap<>();
        for (CoinEntry coin : squadCoins) {
            coins.put(coin.playerId(), coin.amount());
        }
        return Squad.restore(squadId, name, leaderId, rules, memberRoles, level, exp, allianceId,
                coins, squadCoinPool, dailyQuestProgress, disbandedAt);
    }
}