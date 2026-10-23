package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.StrategicTargetDefender;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Strategic arrivals acquire every original battle rally before the target operation lock.
 * This only establishes the lock/read boundary; it does not publish or recover COMBAT yet.
 */
@Service
public final class StrategicTargetMarchAdvancer {
    private static final long LOCKED_REDISPATCH_LIMIT=2;
    private final ConfigRegistry configs;
    private final MarchRepository marches;
    private final StrategicTargetDefender defenders;
    private final SocialAppService social;
    private final StrategicGarrisonWithdrawal targetOperations;

    private static final class TargetContextChanged extends RuntimeException {
        private static final long serialVersionUID=1L;
    }

    public StrategicTargetMarchAdvancer(ConfigRegistry configs,MarchRepository marches,
            StrategicTargetDefender defenders,SocialAppService social,
            StrategicGarrisonWithdrawal targetOperations) {
        this.configs=Objects.requireNonNull(configs);this.marches=Objects.requireNonNull(marches);
        this.defenders=Objects.requireNonNull(defenders);this.social=Objects.requireNonNull(social);
        this.targetOperations=Objects.requireNonNull(targetOperations);
    }

    public boolean advance(March observed,long now,Supplier<Boolean> advance) {
        if(observed==null || advance==null)throw new IllegalArgumentException("原战略行军和推进动作不得为空");
        if(observed.targetType()!=March.TargetType.STRATEGIC_TARGET || !observed.isRallyMarch())
            throw new IllegalArgumentException("战略目标推进必须使用原国家集结行军");
        for(int attempt=0;attempt<LOCKED_REDISPATCH_LIMIT;attempt++) {
            March original=marches.findById(observed.id()).orElse(null);
            if(original==null)return false;
            requireSameTargetRoute(observed,original);
            var captured=needsPlayerDefenderLock(original,now)?readTarget(original):null;
            var rallyIds=new TreeSet<String>();rallyIds.add(requireRally(original));
            if(captured!=null && captured.defender().march()!=null)
                rallyIds.add(requireRally(March.fromSnapshot(captured.defender().march())));
            try {
                return social.runRalliesDepartureLocked(List.copyOf(rallyIds),()->{
                    var lockedMarch=marches.findById(original.id()).orElse(null);
                    if(lockedMarch==null)return false;
                    requireSameTargetRoute(original,lockedMarch);
                    return targetOperations.runMarchLocked(lockedMarch,()->{
                        if(captured!=null && !captured.equals(readTarget(lockedMarch)))
                            throw new TargetContextChanged();
                        return advance.get();
                    });
                });
            } catch(TargetContextChanged changed) {
                // Release every old nation/rally/target lock before re-reading the changed defender.
            }
        }
        throw new IllegalStateException("战略攻守集结上下文持续变化，释放旧锁后仍无法重新分派");
    }

    private boolean needsPlayerDefenderLock(March march,long now) {
        return march.action()==March.Action.ATTACK
                && (march.status()==March.Status.FIGHTING
                    || (march.status()==March.Status.MARCHING && now>=march.arriveAt()));
    }

    private TargetContext readTarget(March march) {
        var target=WorldTargets.from(configs).targets().stream()
                .filter(candidate->candidate.definition().id().equals(march.targetId()))
                .findFirst().orElseThrow(()->new IllegalStateException("战略行军原目标已不在配置目录"));
        if(!target.coord().equals(march.to()))throw new IllegalStateException("战略行军原目标坐标与目标目录不一致");
        var defender=defenders.read(target);
        var anchor=new com.ironoath.web.world.TargetOperationStore.Anchor(march.targetId(),
                com.ironoath.core.world.WorldGenerator.EntityType.valueOf(target.definition().kind().name()),
                target.coord().x(),target.coord().y());
        return new TargetContext(anchor,defender);
    }

    private static String requireRally(March march) {
        if(!march.isRallyMarch() || march.rallyId()==null || march.rallyId().isBlank())
            throw new IllegalStateException("战略战斗缺少原集结锁身份");
        return march.rallyId();
    }

    private static void requireSameTargetRoute(March expected,March latest) {
        if(!expected.playerId().equals(latest.playerId()) || !Objects.equals(expected.rallyId(),latest.rallyId())
                || !expected.targetId().equals(latest.targetId()) || !expected.to().equals(latest.to())
                || expected.targetType()!=latest.targetType() || expected.action()!=latest.action())
            throw new TargetContextChanged();
    }

    private record TargetContext(com.ironoath.web.world.TargetOperationStore.Anchor anchor,
                                 StrategicTargetDefender.Defender defender) {}
}
