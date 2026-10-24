package com.ironoath.web.nation;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.web.service.WorldTargets;
import com.ironoath.web.world.TargetOccupationStore;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** One immutable eight-fortress ownership snapshot shared by a lazy resource-settlement calculation. */
public final class StrategicFortressOutputReadScope {
    public static final long OUTPUT_PERCENT_PER_FORTRESS=200L; // 2.00% in the existing x10000 fixed-point format.
    public static final int FORTRESS_LIMIT=8;

    private final List<String> fortressIds;
    private final Map<String,TargetOccupationStore.Snapshot> occupations;

    private StrategicFortressOutputReadScope(List<String> fortressIds,
            Map<String,TargetOccupationStore.Snapshot> occupations) {
        this.fortressIds=List.copyOf(fortressIds);
        this.occupations=Map.copyOf(occupations);
    }

    public static StrategicFortressOutputReadScope empty() {
        return new StrategicFortressOutputReadScope(List.of(),Map.of());
    }

    public static StrategicFortressOutputReadScope capture(ConfigRegistry configs,TargetOccupationStore occupations) {
        var targets=WorldTargets.from(configs).targets().stream()
                .filter(target->target.definition().kind()==WorldTargetCfg.Kind.FORTRESS)
                .sorted(Comparator.comparing(target->target.definition().id())).toList();
        var ids=targets.stream().map(target->target.definition().id()).toList();
        if(ids.size()>FORTRESS_LIMIT)throw new IllegalStateException("战略收益目录要塞数量超过已裁定上限8座");
        return new StrategicFortressOutputReadScope(ids,occupations.findByTargetIds(ids));
    }

    /** Count the captured ownership state on the requested side of an exact event boundary. */
    public long outputPercent(String nationId,long at,boolean beforeBoundary) {
        if(nationId==null || nationId.isBlank())return 0L;
        if(at<0)throw new IllegalArgumentException("要塞收益计算时刻不得为负");
        long owned=0;
        for(String id:fortressIds) {
            var state=occupations.get(id);
            if(state!=null && nationId.equals(state.nationAt(at,beforeBoundary)))owned++;
        }
        return Math.multiplyExact(Math.min(FORTRESS_LIMIT,owned),OUTPUT_PERCENT_PER_FORTRESS);
    }

    /** Every ownership transition is a lazy-settlement segment boundary; no background timer is used. */
    public List<Long> changeTimes(long afterExclusive,long throughInclusive) {
        var times=new TreeSet<Long>();
        for(String id:fortressIds) {
            var state=occupations.get(id);
            if(state!=null)times.addAll(state.ownershipChangeTimes(afterExclusive,throughInclusive));
        }
        return List.copyOf(times);
    }

    public Collection<String> fortressIds() { return fortressIds; }
}
