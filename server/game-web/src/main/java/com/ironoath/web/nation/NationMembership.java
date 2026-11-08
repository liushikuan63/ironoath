package com.ironoath.web.nation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.ironoath.core.nation.Nation;
import com.ironoath.core.social.Alliance;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：把「玩家 ↔ 国家」这一跳<b>两个方向</b>都收在一处（联盟 ⊂ 国家，国籍跟随联盟）：
 * 正向 {@link #ofPlayer}（玩家 → 联盟 → 国家），反方向 {@link #playerIdsOf}
 * （国家 → 成员联盟 → 玩家）。
 * 依赖：{@link SocialStore}（玩家 ↔ 联盟）、{@link NationStore}（联盟 → 国家）。
 *
 * <p><b>为什么单独一个类而不是各处自己写两跳</b>：这一跳已经有三个读者 ——
 * 国战读视图（{@code WarAppService}）、击杀归属（{@code BattleReportService}）、
 * 国家榜投影（{@code RankBoardService} 走的是批量口 {@code nationsByAlliance}，形状不同故不共用）。
 * 前两跳如果各写一遍，最先分叉的是<b>「没有联盟」与「有联盟但没入籍」这两种缺席</b>怎么处理：
 * 一处回 empty、另一处抛「你不在任何联盟中」，症状是同一个人在一个面板看得到国家、在另一个面板看不见。
 *
 * <p><b>返回的是副本且不做 {@code NationLeaders.bind}</b>：议员席那一项需要注入盟主查询
 * （{@code NationAppService} 那边才做），而国战与击杀归属只读官职、外交与成员表 ——
 * 在这里 bind 等于给每个调用方都付一次联盟查询的代价，还让人误以为这份对象能算议员席。
 */
@Component
public class NationMembership {

    private final SocialStore social;
    private final NationStore nations;

    public NationMembership(SocialStore social, NationStore nations) {
        this.social = social;
        this.nations = nations;
    }

    /**
     * 这个玩家当前所属的国家。
     *
     * @return 没有联盟、或联盟没入籍、或玩家 id 为空时都是 {@code empty} ——
     *         <b>这三种缺席对调用方是同一件事</b>（「他不在任何国家里」），
     *         分开返回会让上层以为存在"有联盟但查不到"这种需要单独处理的状态
     */
    public Optional<Nation> ofPlayer(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return Optional.empty();
        }
        return social.allianceOf(playerId).flatMap(alliance -> nations.findByAlliance(alliance.id()));
    }

    /** 只有国家 id 的版本：给"不需要整份聚合"的调用方（击杀归属只要 id，读整档纯属浪费一次反序列化）。 */
    public String nationIdOf(String playerId) {
        return ofPlayer(playerId).map(Nation::id).orElse(null);
    }

    /**
     * 一个国家当前的全部成员 playerId（反方向那一跳：国家 → 成员联盟 → 盟内成员）。
     *
     * <p>此方法只返回当前花名册，不能用来推断历史成员资格；历史战事时长请使用
     * {@link #effectiveMemberMillisByPlayer(Nation, long, long)}。
     *
     * <p><b>为什么必须是一次 {@link SocialStore#allAlliances()}，而不是按成员联盟逐个
     * {@code allianceById}</b>：一个国能容纳几个盟是按等级发的
     * （{@code Nation.maxAllianceCount()}，读 {@code nation_config}），而每个盟最多几十到几百人 ——
     * 逐盟点查的往返数跟着<b>盟数</b>涨。正向那条口（{@link #ofPlayer}）之所以能点查是因为它一跳
     * 只涉及<b>一个人</b>；这里问的是<b>一整国</b>，形状不同，不能照抄。
     * 与 {@code SocialStore.alliancesOf} / {@code NationStore.nationsByAlliance} 是同一条判断，
     * 只是方向相反：<b>那两口是"一批人 → 他们的组织"，这一口是"一个组织 → 他们的人"</b>。
     *
     * <p><b>顺序按 {@link Nation#memberAllianceIds()} 的登记顺序</b>，而不是 {@code allAlliances()}
     * 返回的 id 升序：前者是<b>这个国家自己的记录</b>，两套存储（内存与 Mongo）里同一国的成员联盟
     * 顺序相同，于是"这个国家的花名册"在 dev 与生产给出同一份序列。按 id 排序会让顺序取决于
     * 联盟 id 的字典序 —— 那对玩家没有任何意义，却足以让两次读出来的榜长得不一样。
     *
     * <p><b>去重是防御，不是业务</b>：域上一个人只会在一个盟里（{@code Alliance.members} 的键唯一，
     * 而一个盟只入一个国家），所以重复只可能来自脏档。留这一手的原因是它的下游是<b>发钱</b> ——
     * 一个 id 出现两次就是同一个人在同一场仗里领两份赛季分，那种账没人能回滚。
     *
     * <p><b>成员联盟的档查不到就当没有这一盟</b>：解散联盟会整档删除（两套实现都删，见
     * {@code SocialStore#removeAlliance}），而国家侧的成员联盟行在同一条流程里被清掉，
     * 所以真出现在这里的是"档没了"而不是"还没算完"。给它补一份空名单比抛异常好 ——
     * 调用方是结算发奖，为一条脏档让一整场国战发不了奖是把数据问题转嫁给玩家。
     *
     * @param nation 要花名册的那个国家；{@code null}（或已解散、成员联盟表为空）都回空表 ——
     *               解散时 {@code memberAlliances} 已被 {@code Nation.tearDown} 清空，
     *               所以"亡国没有成员"这一条不需要在这里再判一次
     * @return 去重后的成员 id，不可变；空表表示这个国家没有人
     */
    public List<String> playerIdsOf(Nation nation) {
        if (nation == null) {
            return List.of();
        }
        Set<String> memberAllianceIds = nation.memberAllianceIds();
        if (memberAllianceIds.isEmpty()) {
            return List.of();
        }
        // 一次批量读，之后全在 Java 侧筛：这一步的往返数恒为 1，与盟数、人数都无关
        Map<String, Alliance> byId = new LinkedHashMap<>();
        for (Alliance alliance : social.allAlliances()) {
            byId.put(alliance.id(), alliance);
        }
        List<String> playerIds = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String allianceId : memberAllianceIds) {
            Alliance alliance = byId.get(allianceId);
            if (alliance == null) {
                continue;
            }
            for (String playerId : alliance.memberIds()) {
                if (seen.add(playerId)) {
                    playerIds.add(playerId);
                }
            }
        }
        return List.copyOf(playerIds);
    }

    /**
     * 每名玩家在指定国家、指定半开区间内的持久成员时长（毫秒）。
     *
     * <p>此口只读Social的联盟成员历史与Nation的联盟归属历史，再与战事区间求交；
     * <b>不会用当前花名册补造过去</b>。旧成员若没有可辩护的历史起点就没有可计时区间，
     * 因而不能凭重试当天名单领取旧战奖金。
     *
     * <p>多个成员/国家历史段先求交再按玩家合并。若同一玩家在同一国家出现重叠的有效区间，
     * 说明持久历史自相矛盾；这里 fail closed，而不是把重叠时长双计。
     */
    public Map<String, Long> effectiveMemberMillisByPlayer(Nation nation, long warStartedAt, long settledAt) {
        Objects.requireNonNull(nation, "国战成员时长计算必须带持久国家历史");
        if (warStartedAt < 0L || settledAt < warStartedAt) {
            throw new IllegalArgumentException("国战成员时长查询区间无效：startedAt="
                    + warStartedAt + " settledAt=" + settledAt);
        }
        if (settledAt == warStartedAt) {
            return Map.of();
        }

        Set<String> allianceIds = new TreeSet<>();
        for (Nation.AllianceMembershipPeriod period : nation.formerAllianceMemberships()) {
            allianceIds.add(period.allianceId());
        }
        allianceIds.addAll(nation.memberAllianceIds());

        Map<String, List<TimeRange>> rangesByPlayer = new HashMap<>();
        for (String allianceId : allianceIds) {
            List<SocialStore.AllianceMemberEpoch> memberEpochs = Objects.requireNonNull(
                    social.allianceMemberEpochs(allianceId), "联盟成员历史不得为 null：allianceId=" + allianceId);
            List<NationStore.AllianceNationEpoch> nationEpochs = Objects.requireNonNull(
                    nations.allianceNationEpochs(allianceId), "联盟国家历史不得为 null：allianceId=" + allianceId);

            for (NationStore.AllianceNationEpoch nationEpoch : nationEpochs) {
                if (!nation.id().equals(nationEpoch.nationId())) {
                    continue;
                }
                long countryFrom = Math.max(warStartedAt, nationEpoch.fromAt());
                long countryUntil = Math.min(settledAt, nationEpoch.untilAt());
                if (countryFrom >= countryUntil) {
                    continue;
                }
                for (SocialStore.AllianceMemberEpoch memberEpoch : memberEpochs) {
                    long from = Math.max(countryFrom, memberEpoch.fromAt());
                    long until = Math.min(countryUntil, memberEpoch.untilAt());
                    if (from < until) {
                        rangesByPlayer.computeIfAbsent(memberEpoch.playerId(), ignored -> new ArrayList<>())
                                .add(new TimeRange(from, until));
                    }
                }
            }
        }

        long warDuration = settledAt - warStartedAt;
        Map<String, Long> durations = new TreeMap<>();
        for (Map.Entry<String, List<TimeRange>> entry : rangesByPlayer.entrySet()) {
            List<TimeRange> ranges = entry.getValue();
            ranges.sort(Comparator.comparingLong(TimeRange::fromAt).thenComparingLong(TimeRange::untilAt));
            long total = 0L;
            long previousUntil = -1L;
            for (TimeRange range : ranges) {
                if (range.fromAt() < previousUntil) {
                    throw new IllegalStateException("同一玩家国战成员有效区间重叠，拒绝重复计时：playerId="
                            + entry.getKey() + " nationId=" + nation.id());
                }
                total = Math.addExact(total, range.untilAt() - range.fromAt());
                previousUntil = range.untilAt();
            }
            if (total > warDuration) {
                throw new IllegalStateException("玩家国战成员有效时长超过战事全期：playerId="
                        + entry.getKey() + " nationId=" + nation.id());
            }
            if (total > 0L) {
                durations.put(entry.getKey(), total);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(durations));
    }

    private record TimeRange(long fromAt, long untilAt) {}
}
