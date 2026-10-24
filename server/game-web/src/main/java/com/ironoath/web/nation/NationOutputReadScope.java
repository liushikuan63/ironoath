package com.ironoath.web.nation;

import java.util.Objects;
import java.util.List;
import java.util.function.Supplier;
import com.ironoath.core.nation.Nation;

/** One player's successful nation lookup within one output calculation, never a tick/global cache. */
public final class NationOutputReadScope {
    public record MembershipTimeline(String allianceId,List<NationStore.AllianceNationEpoch> epochs) {
        public MembershipTimeline { epochs=epochs==null?List.of():List.copyOf(epochs); }
        public String nationIdAt(long at,boolean beforeBoundary) {
            if(allianceId==null || allianceId.isBlank())return null;
            var matches=epochs.stream().filter(epoch->epoch.contains(at,beforeBoundary)).toList();
            if(matches.size()>1)throw new IllegalStateException("联盟收益时间轴重叠 allianceId="+allianceId+" at="+at);
            return matches.isEmpty()?null:matches.get(0).nationId();
        }
        public List<Long> changeTimes(long afterExclusive,long throughInclusive) {
            var times=new java.util.TreeSet<Long>();
            for(var epoch:epochs) {
                if(epoch.fromAt()>afterExclusive && epoch.fromAt()<=throughInclusive)times.add(epoch.fromAt());
                if(epoch.untilAt()>afterExclusive && epoch.untilAt()<=throughInclusive && epoch.untilAt()!=Long.MAX_VALUE)
                    times.add(epoch.untilAt());
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
