package com.ironoath.web.nation;

import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;
import com.ironoath.core.nation.Nation;

/** One player's successful nation lookup within one output calculation, never a tick/global cache. */
public final class NationOutputReadScope {
    public record AllianceEligibilityEpoch(String allianceId,long fromAt,long untilAt,
                                           List<NationStore.AllianceNationEpoch> nationEpochs) {
        public AllianceEligibilityEpoch {
            if(allianceId==null || allianceId.isBlank() || fromAt<0 || untilAt<fromAt)
                throw new IllegalArgumentException("玩家国家资格时间段无效");
            nationEpochs=nationEpochs==null?List.of():List.copyOf(nationEpochs);
        }
        public boolean contains(long at,boolean beforeBoundary) {
            return beforeBoundary?fromAt<at && at<=untilAt:fromAt<=at && at<untilAt;
        }
    }

    public record MembershipTimeline(String allianceId,List<AllianceEligibilityEpoch> epochs) {
        public MembershipTimeline {
            epochs=epochs==null?List.of():epochs.stream()
                    .sorted(java.util.Comparator.comparingLong(AllianceEligibilityEpoch::fromAt)
                            .thenComparing(AllianceEligibilityEpoch::allianceId)).toList();
            long previousUntil=-1;
            for(var epoch:epochs) {
                if(epoch.fromAt()<previousUntil)
                    throw new IllegalStateException("玩家国家资格时间段重叠 player allianceId="+epoch.allianceId());
                previousUntil=Math.max(previousUntil,epoch.untilAt());
            }
        }
        public String nationIdAt(long at,boolean beforeBoundary) {
            var memberships=epochs.stream().filter(epoch->epoch.contains(at,beforeBoundary)).toList();
            if(memberships.size()>1)throw new IllegalStateException("玩家联盟成员时间轴重叠 at="+at);
            if(memberships.isEmpty())return null;
            var membership=memberships.get(0);
            var nations=membership.nationEpochs().stream().filter(epoch->epoch.contains(at,beforeBoundary)).toList();
            if(nations.size()>1)throw new IllegalStateException("联盟收益时间轴重叠 allianceId="+membership.allianceId()+" at="+at);
            return nations.isEmpty()?null:nations.get(0).nationId();
        }
        public List<Long> changeTimes(long afterExclusive,long throughInclusive) {
            var times=new java.util.TreeSet<Long>();
            for(var membership:epochs) {
                if(membership.fromAt()>afterExclusive && membership.fromAt()<=throughInclusive)times.add(membership.fromAt());
                if(membership.untilAt()>afterExclusive && membership.untilAt()<=throughInclusive
                        && membership.untilAt()!=Long.MAX_VALUE)times.add(membership.untilAt());
                for(var nation:membership.nationEpochs()) {
                    if(nation.fromAt()>afterExclusive && nation.fromAt()<=throughInclusive
                            && nation.fromAt()>=membership.fromAt() && nation.fromAt()<=membership.untilAt())
                        times.add(nation.fromAt());
                    if(nation.untilAt()>afterExclusive && nation.untilAt()<=throughInclusive
                            && nation.untilAt()!=Long.MAX_VALUE && nation.untilAt()>=membership.fromAt()
                            && nation.untilAt()<=membership.untilAt())times.add(nation.untilAt());
                }
            }
            return List.copyOf(times);
        }
    }

    private final String playerId;
    private final MembershipTimeline membershipTimeline;
    private boolean resolved;
    private Nation snapshot;
    private String allianceId;
    private NationStore nations;

    public NationOutputReadScope(String playerId) { this(playerId,null); }
    public NationOutputReadScope(String playerId,MembershipTimeline membershipTimeline) {
        this.playerId=playerId;this.membershipTimeline=membershipTimeline;
        if(membershipTimeline!=null)this.allianceId=membershipTimeline.allianceId();
    }

    String knownAllianceId() { return membershipTimeline==null?null:membershipTimeline.allianceId(); }
    boolean hasMembershipTimeline() { return membershipTimeline!=null; }

    Nation resolve(String requestedPlayer, Supplier<Nation> lookup) {
        if (!Objects.equals(playerId, requestedPlayer)) {
            throw new IllegalArgumentException("国家产出读取范围与玩家不一致");
        }
        if (!resolved) {
            // Assign only after success: null is a valid absence; exceptions stay retryable.
            snapshot = lookup.get();
            resolved = true;
        }
        return snapshot;
    }

    void rememberAllianceId(String requestedPlayer,String allianceId,NationStore nations) {
        if(!Objects.equals(playerId,requestedPlayer))throw new IllegalArgumentException("国家产出读取范围与玩家不一致");
        if(membershipTimeline!=null && !Objects.equals(membershipTimeline.allianceId(),allianceId))
            throw new IllegalStateException("国家成员时间轴与本次读取的Alliance不一致");
        this.allianceId=allianceId;this.nations=Objects.requireNonNull(nations);
    }

    /** National eligibility is resolved at each rate segment, even when the alliance has since left its nation. */
    public String nationIdAt(long at,boolean beforeBoundary) {
        if(!resolved)return null;
        if(membershipTimeline!=null)return membershipTimeline.nationIdAt(at,beforeBoundary);
        if(allianceId==null || nations==null)return null;
        if(snapshot!=null && snapshot.allianceMemberAt(allianceId,at,beforeBoundary))return snapshot.id();
        return nations.nationAtAlliance(allianceId,at,beforeBoundary).map(Nation::id).orElse(null);
    }

}
