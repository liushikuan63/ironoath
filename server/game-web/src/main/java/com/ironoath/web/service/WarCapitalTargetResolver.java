package com.ironoath.web.service;

import com.ironoath.config.ConfigRegistry;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.nation.WarStore;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/** Resolves the single world CAPITAL slot from the latest persisted War target Nation. */
@Component
public final class WarCapitalTargetResolver {
    public record Directory(WorldTargets targets, long version, String targetNationId,
                            long targetNationVersion, long warStartedAt) {}

    private final NationStore nations;
    private final WarStore wars;
    private final WorldRepository world;
    private final NationCapitalRelocationService relocations;
    private final ThreadLocal<Boolean> stableTargetScope=ThreadLocal.withInitial(()->false);

    public WarCapitalTargetResolver(NationStore nations, WarStore wars, WorldRepository world) {
        this(nations, wars, world, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WarCapitalTargetResolver(NationStore nations, WarStore wars, WorldRepository world,
                                    NationCapitalRelocationService relocations) {
        this.nations = Objects.requireNonNull(nations);
        this.wars = Objects.requireNonNull(wars);
        this.world = Objects.requireNonNull(world);
        this.relocations = relocations;
    }

    /** Old Wars with no persisted target identity keep the legacy configured candidate and coordinates. */
    public Directory resolve(ConfigRegistry configuration) {
        Objects.requireNonNull(configuration, "captured configuration must exist");
        if (relocations != null&&!stableTargetScope.get()) relocations.recoverPending();
        WorldTargets targets = WorldTargets.from(configuration);
        long version = configuration.rawTable("world_target").version();
        var latest = wars.findLatest().orElse(null);
        if (latest == null || latest.targetNationId() == null) {
            return new Directory(targets, version, null, -1L, -1L);
        }
        Nation target = nations.findById(latest.targetNationId()).orElseThrow(
                () -> new IllegalStateException("最新War目标国家快照缺失，拒绝退回静态王城坐标"));
        Coord capital = Coord.of(Math.toIntExact(target.capitalX()), Math.toIntExact(target.capitalY()));
        if (!capital.withinWorld(Math.toIntExact(configuration.longParam("WORLD_SIZE")))) {
            throw new IllegalStateException("War目标国都城坐标越界，拒绝投影动态王城");
        }
        if (world.cityAt(capital).isPresent()) {
            throw new IllegalStateException("War目标国都城坐标与玩家城冲突，拒绝投影动态王城");
        }
        targets = targets.withCapitalCoord(capital);
        long versionStamp = Math.addExact(latest.startedAt(), target.version());
        return new Directory(targets, Math.addExact(version, versionStamp), target.id(), target.version(), latest.startedAt());
    }

    public void recoverPendingRelocations() {
        if(relocations!=null)relocations.recoverPending();
    }

    public <T> T withStableTarget(Supplier<T> action) {
        Objects.requireNonNull(action,"action must exist");
        if(relocations==null||stableTargetScope.get())return action.get();
        relocations.recoverPending();
        return withStableTargetAfterRecovery(action);
    }

    /** Use only after recovery ran outside any Nation lock; recheck under the shared target lock. */
    public <T> T withStableTargetAfterRecovery(Supplier<T> action) {
        Objects.requireNonNull(action,"action must exist");
        if(relocations==null||stableTargetScope.get())return action.get();
        return relocations.withCapitalTargetLock(()->{
            if(relocations.hasPendingRelocations())
                throw new BizException(ErrorCode.NATION_LOCKED,"Capital relocation recovery is still pending");
            stableTargetScope.set(true);
            try{return action.get();}
            finally{stableTargetScope.remove();}
        });
    }
}
