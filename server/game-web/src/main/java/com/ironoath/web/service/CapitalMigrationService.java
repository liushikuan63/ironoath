package com.ironoath.web.service;

import com.ironoath.common.BizException;
import com.ironoath.common.ErrorCode;
import com.ironoath.common.time.TimeService;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.WorldTargetCfg;
import com.ironoath.core.idempotency.IdempotencyStore;
import com.ironoath.core.lock.PlayerLock;
import com.ironoath.core.march.March;
import com.ironoath.core.march.MarchRepository;
import com.ironoath.core.nation.Nation;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.world.Coord;
import com.ironoath.core.world.WorldGenerator;
import com.ironoath.core.world.WorldRepository;
import com.ironoath.web.bot.BotRegistry;
import com.ironoath.web.dto.generated.NationCapitalMigrationReq;
import com.ironoath.web.dto.generated.NationCapitalMigrationResp;
import com.ironoath.web.nation.NationLeaders;
import com.ironoath.web.nation.NationOperationLock;
import com.ironoath.web.nation.NationStore;
import com.ironoath.web.social.SocialStore;
import com.ironoath.web.world.StrategicGarrisonWithdrawal;
import com.ironoath.web.world.TargetOccupationStore;
import com.ironoath.web.world.TargetOperationStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * B13 capital move: validate authority and world state, then save only the Nation coordinate through CAS.
 *
 * <p>Lock order is actor PlayerLock, optional King PlayerLock for an officer delegation, sorted old/new world-cell
 * locks, the existing CAPITAL target-operation lock, then NationOperationLock. March creation and rally departure
 * use the same cell-before-target order. No new PlayerLock is acquired after entering NationOperationLock.
 * NationStore.save(expectedVersion) remains the final protection against a writer outside this lock facade, and the
 * save mutates the loaded Nation snapshot so membership history and unrelated Nation fields are retained.
 */
@Service
public final class CapitalMigrationService {
    private static final long LOCK_TIMEOUT_MS = 3_000L;
    private static final long KING_OFFLINE_MILLIS = 7L * 24L * 60L * 60L * 1_000L;

    private static final class NationContextChanged extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final NationStore nations;
    private final NationLeaders leaders;
    private final SocialStore social;
    private final PlayerRepository players;
    private final PlayerLock playerLocks;
    private final NationOperationLock nationLocks;
    private final IdempotencyStore idempotency;
    private final TimeService time;
    private final ConfigRegistry configs;
    private final BotRegistry bots;
    private final WorldRepository world;
    private final WarCapitalTargetResolver capitalTargets;
    private final MarchRepository marches;
    private final TargetOperationStore operations;
    private final TargetOccupationStore occupations;
    private final StrategicGarrisonWithdrawal targetLock;

    public CapitalMigrationService(NationStore nations, NationLeaders leaders, SocialStore social,
                                   PlayerRepository players, PlayerLock playerLocks,
                                   IdempotencyStore idempotency, TimeService time,
                                   ConfigRegistry configs, BotRegistry bots, WorldRepository world,
                                   WarCapitalTargetResolver capitalTargets, MarchRepository marches,
                                   TargetOperationStore operations, TargetOccupationStore occupations,
                                   StrategicGarrisonWithdrawal targetLock) {
        this.nations = Objects.requireNonNull(nations);
        this.leaders = Objects.requireNonNull(leaders);
        this.social = Objects.requireNonNull(social);
        this.players = Objects.requireNonNull(players);
        this.playerLocks = Objects.requireNonNull(playerLocks);
        this.nationLocks = new NationOperationLock(playerLocks);
        this.idempotency = Objects.requireNonNull(idempotency);
        this.time = Objects.requireNonNull(time);
        this.configs = Objects.requireNonNull(configs);
        this.bots = Objects.requireNonNull(bots);
        this.world = Objects.requireNonNull(world);
        this.capitalTargets = Objects.requireNonNull(capitalTargets);
        this.marches = Objects.requireNonNull(marches);
        this.operations = Objects.requireNonNull(operations);
        this.occupations = Objects.requireNonNull(occupations);
        this.targetLock = Objects.requireNonNull(targetLock);
    }

    public NationCapitalMigrationResp move(String playerId, NationCapitalMigrationReq request) {
        if (playerId == null || playerId.isBlank()) {
            throw new BizException(ErrorCode.PLAYER_NOT_FOUND, "缺少玩家身份");
        }
        if (request == null || request.requestId() == null || request.requestId().isBlank()) {
            throw new BizException(ErrorCode.REQUEST_ID_MISSING, "迁都必须带 requestId");
        }
        long requestAt = time.serverNow();
        long ttlMs = Math.multiplyExact(configs.longParam("REQUEST_ID_TTL_SECONDS"), 1_000L);
        if (!idempotency.tryAcquire(request.requestId(), requestAt, ttlMs)) {
            throw new BizException(ErrorCode.REQUEST_DUPLICATED, "requestId=" + request.requestId());
        }
        try {
            return playerLocks.runLocked(playerId, LOCK_TIMEOUT_MS,
                    () -> moveUnderOperatorLock(playerId, request));
        } catch (RuntimeException failure) {
            // Coordinates and Nation state are not changed on validation/CAS failure. A client may retry.
            idempotency.release(request.requestId());
            throw failure;
        }
    }

    private NationCapitalMigrationResp moveUnderOperatorLock(String playerId, NationCapitalMigrationReq request) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Nation observed = resolveNation(playerId);
            Nation.Office office = officeOf(observed, playerId);
            requireMayHoldOffice(playerId, office);
            if (office == Nation.Office.KING) {
                try {
                    return moveObservedNation(playerId, request, observed);
                } catch (NationContextChanged changed) {
                    // Re-read after releasing the stale coordinate/target/nation locks.
                }
                continue;
            }

            try {
                return playerLocks.runLocked(observed.kingId(), LOCK_TIMEOUT_MS, () -> {
                    long now = time.serverNow();
                    if (!mayDelegateByKingOffline(playerId, observed.kingId(), now,
                            () -> players.findByPlayerId(observed.kingId())
                                    .map(save -> OptionalLong.of(save.lastLoginAt()))
                                    .orElseGet(OptionalLong::empty))) {
                        throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED,
                                "国王登录记录不可验证或未离线满一周，官员不能代行迁都");
                    }
                    return moveObservedNation(playerId, request, observed);
                });
            } catch (NationContextChanged changed) {
                // The King or capital changed while waiting for the locks; release and resolve again.
            }
        }
        throw new BizException(ErrorCode.NATION_LOCKED, "国家状态持续变化，请刷新后重试迁都");
    }

    private NationCapitalMigrationResp moveObservedNation(String playerId, NationCapitalMigrationReq request,
                                                           Nation observed) {
        long worldSize = configs.longParam("WORLD_SIZE");
        Coord destination = requireWorldCoordinate(request.x(), request.y(), worldSize);
        Coord oldCapital = requireWorldCoordinate(observed.capitalX(), observed.capitalY(), worldSize);
        if (oldCapital.equals(destination)) {
            return nationLocks.runLocked(observed.id(), () -> {
                Nation current = nations.findById(observed.id())
                        .filter(nation -> !nation.isDisbanded())
                        .map(leaders::bind)
                        .orElseThrow(() -> new BizException(ErrorCode.NATION_NOT_FOUND, "国家不存在或已解散"));
                if (!Objects.equals(current.kingId(), observed.kingId())
                        || current.capitalX() != observed.capitalX()
                        || current.capitalY() != observed.capitalY()) throw new NationContextChanged();
                requireCurrentAuthority(playerId, current);
                return response(current, time.serverNow());
            });
        }

        String capitalTargetId = capitalTargetId();
        List<Coord> coordinates = List.of(oldCapital, destination);
        return runCoordinateLocks(playerLocks, coordinates, () ->
                targetLock.runTargetOperationLocked(capitalTargetId, () ->
                        nationLocks.runLocked(observed.id(), () -> {
                            Nation current = nations.findById(observed.id())
                                    .filter(nation -> !nation.isDisbanded())
                                    .map(leaders::bind)
                                    .orElseThrow(() -> new BizException(ErrorCode.NATION_NOT_FOUND,
                                            "国家不存在或已解散"));
                            if (!Objects.equals(current.kingId(), observed.kingId())
                                    || current.capitalX() != observed.capitalX()
                                    || current.capitalY() != observed.capitalY()) {
                                throw new NationContextChanged();
                            }
                            requireCurrentAuthority(playerId, current);
                            Coord currentCapital = requireWorldCoordinate(
                                    current.capitalX(), current.capitalY(), worldSize);
                            if (!currentCapital.equals(oldCapital)) throw new NationContextChanged();
                            if (destination.equals(currentCapital)) return response(current, time.serverNow());

                            WarCapitalTargetResolver.Directory directory = capitalTargets.resolve(configs);
                            requireLegalDestination(destination, directory.targets(), worldSize);
                            requirePendingRallyDeparturesClear(currentCapital, destination);
                            requireDestinationMarchesIdle(destination);
                            requireOldCapitalClear(current, currentCapital, capitalTargetId);

                            long expectedVersion = current.version();
                            current.moveCapital(destination.x(), destination.y());
                            try {
                                nations.save(current, expectedVersion);
                            } catch (IllegalStateException concurrentWrite) {
                                throw new BizException(ErrorCode.NATION_LOCKED,
                                        "国家状态已变化，迁都未覆盖并发修改；请刷新后重试");
                            }
                            return response(current, time.serverNow());
                        })));
    }

    private Nation resolveNation(String playerId) {
        List<Nation> kings = nations.all().stream()
                .filter(nation -> !nation.isDisbanded() && playerId.equals(nation.kingId()))
                .toList();
        if (kings.size() > 1) {
            throw new IllegalStateException("同一玩家对应多个在册国家国王，拒绝迁都 playerId=" + playerId);
        }
        if (!kings.isEmpty()) return leaders.bind(kings.get(0));

        var alliance = social.allianceOf(playerId).orElse(null);
        if (alliance == null) throw new BizException(ErrorCode.NATION_NOT_FOUND, "你没有可迁都的国家");
        return nations.findByAlliance(alliance.id())
                .filter(nation -> !nation.isDisbanded())
                .map(leaders::bind)
                .orElseThrow(() -> new BizException(ErrorCode.NATION_NOT_FOUND, "你的联盟不属于任何在册国家"));
    }

    private Nation.Office officeOf(Nation nation, String playerId) {
        if (playerId.equals(nation.kingId())) return Nation.Office.KING;
        Nation.Office office = nation.officeOf(playerId);
        if (office == null) {
            throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED, "只有国王或本国官职持有人可以迁都");
        }
        return office;
    }

    private void requireCurrentAuthority(String playerId, Nation nation) {
        Nation.Office office = officeOf(nation, playerId);
        requireMayHoldOffice(playerId, office);
        if (office != Nation.Office.KING) {
            var alliance = social.allianceOf(playerId).orElse(null);
            if (alliance == null || nations.findByAlliance(alliance.id())
                    .filter(current -> current.id().equals(nation.id()))
                    .isEmpty()) {
                throw new BizException(ErrorCode.SOCIAL_PERMISSION_DENIED, "官职已失效或已离开本国");
            }
        }
    }

    private void requireMayHoldOffice(String playerId, Nation.Office office) {
        bots.requireMayHoldOffice(playerId, "NATION", office == Nation.Office.KING, true,
                office == Nation.Office.KING ? "国王" : "国家官职");
    }

    static boolean mayDelegateByKingOffline(String operatorId, String kingId, long now,
                                             Supplier<OptionalLong> lastLoginRead) {
        if (operatorId != null && operatorId.equals(kingId)) return true;
        if (operatorId == null || kingId == null || kingId.isBlank() || now <= 0 || lastLoginRead == null) {
            return false;
        }
        try {
            OptionalLong lastLogin = lastLoginRead.get();
            if (lastLogin == null || lastLogin.isEmpty()) return false;
            long loggedAt = lastLogin.getAsLong();
            return loggedAt > 0 && loggedAt <= now && now - loggedAt >= KING_OFFLINE_MILLIS;
        } catch (RuntimeException readFailure) {
            return false;
        }
    }

    private String capitalTargetId() {
        List<WorldTargets.Target> capitals = WorldTargets.from(configs).targets().stream()
                .filter(target -> target.definition().kind() == WorldTargetCfg.Kind.CAPITAL).toList();
        if (capitals.size() != 1) throw new IllegalStateException("迁都要求世界目标目录恰好含一个CAPITAL");
        return capitals.get(0).definition().id();
    }

    private void requireLegalDestination(Coord destination, WorldTargets targets, long worldSize) {
        if (!destination.withinWorld(Math.toIntExact(worldSize))) {
            throw invalidCoordinate("目标坐标超出当前世界边界");
        }
        if (world.cityAt(destination).isPresent()) {
            throw invalidCoordinate("目标坐标已有玩家城");
        }
        if (targets.reserved(destination)) {
            throw invalidCoordinate("目标坐标被保留战略目标占用");
        }
    }

    private void requireDestinationMarchesIdle(Coord destination) {
        int chunkSize = Math.toIntExact(configs.longParam("WORLD_CHUNK_SIZE"));
        String chunkKey = destination.chunkKey(chunkSize);
        for (March march : marches.findByChunkKeys(List.of(chunkKey))) {
            if (!march.to().equals(destination)) continue;
            if (march.status() == March.Status.MARCHING || march.status() == March.Status.FIGHTING
                    || march.status() == March.Status.STATIONED || march.status() == March.Status.GATHERING
                    || (march.status() == March.Status.RETURNING && march.hasGarrisonOwnership())) {
                throw new BizException(ErrorCode.MARCH_STATE_INVALID,
                        "目标坐标仍有行军或驻军操作，不能在其行进途中改变地图目标");
            }
        }
    }

    private void requirePendingRallyDeparturesClear(Coord oldCapital, Coord destination) {
        for (var plan : social.pendingRallyDepartures()) {
            Coord target;
            if (plan.march() != null) {
                target = plan.march().to();
            } else {
                var rally = social.rallyOf(plan.rallyId()).orElse(null);
                if (rally == null) continue;
                target = Coord.of(Math.toIntExact(rally.targetX()), Math.toIntExact(rally.targetY()));
            }
            if (target.equals(oldCapital) || target.equals(destination)) {
                throw migrationBlocked("旧都或新都仍有待恢复的集结出发计划");
            }
        }
    }

    private void requireOldCapitalClear(Nation nation, Coord oldCapital, String capitalTargetId) {
        TargetOperationStore.Snapshot slot = operations.findByTargetId(capitalTargetId).orElse(null);
        if (slot != null && slot.anchor().x() == oldCapital.x() && slot.anchor().y() == oldCapital.y()
                && !slot.completed()) {
            throw migrationBlocked("旧王城仍有未完成的目标操作");
        }

        TargetOccupationStore.Snapshot occupation = occupations.findByTargetIds(List.of(capitalTargetId))
                .get(capitalTargetId);
        if (occupation != null && occupation.x() == oldCapital.x() && occupation.y() == oldCapital.y()) {
            if (occupation.garrison() == null) throw migrationBlocked("旧王城驻军状态缺失，不能确认已撤防");
            if (occupation.garrison().kind() == TargetOccupationStore.Garrison.Kind.STATIONED) {
                throw migrationBlocked("旧王城驻军必须先撤防并返城");
            }
        }

        int chunkSize = Math.toIntExact(configs.longParam("WORLD_CHUNK_SIZE"));
        for (March march : marches.findByChunkKeys(List.of(oldCapital.chunkKey(chunkSize)))) {
            if (!march.to().equals(oldCapital) || march.targetType() != March.TargetType.STRATEGIC_TARGET
                    || !capitalTargetId.equals(march.targetId()) || march.action() != March.Action.ATTACK) {
                continue;
            }
            if (unfinishedAttackToOldCapital(march, slot)) {
                throw migrationBlocked("旧王城有尚未结算或返城完成的攻击行军");
            }
        }
    }

    private static boolean unfinishedAttackToOldCapital(March march, TargetOperationStore.Snapshot slot) {
        return switch (march.status()) {
            case MARCHING, FIGHTING, STATIONED -> true;
            case GATHERING -> true;
            case RETURNING -> march.hasGarrisonOwnership() || !completedCombatContains(slot, march.id());
        };
    }

    private static boolean completedCombatContains(TargetOperationStore.Snapshot slot, String marchId) {
        if (slot == null || !slot.completed() || slot.kind() != TargetOperationStore.Kind.COMBAT) return false;
        var battle = slot.combatPlan().battle();
        if (battle.attacker().march().id().equals(marchId)) return true;
        return battle.defender() != null && battle.defender().march().id().equals(marchId);
    }

    private static Coord requireWorldCoordinate(long x, long y, long worldSize) {
        if (worldSize < 1 || worldSize > Integer.MAX_VALUE
                || x < 0 || y < 0 || x >= worldSize || y >= worldSize) {
            throw invalidCoordinate("世界坐标必须位于0到" + (worldSize - 1L) + "之间");
        }
        return Coord.of(Math.toIntExact(x), Math.toIntExact(y));
    }

    private static BizException invalidCoordinate(String detail) {
        return new BizException(ErrorCode.WORLD_TARGET_INVALID, detail);
    }

    private static BizException migrationBlocked(String detail) {
        return new BizException(ErrorCode.MARCH_STATE_INVALID, detail);
    }

    private static NationCapitalMigrationResp response(Nation nation, long now) {
        return new NationCapitalMigrationResp(nation.id(), nation.capitalX(), nation.capitalY(), now);
    }

    /** March creation and relocation share one per-cell lock; no global map lock is introduced. */
    public static <T> T runCoordinateLocked(PlayerLock locks, Coord coordinate, Supplier<T> action) {
        Objects.requireNonNull(locks, "player locks");
        Objects.requireNonNull(coordinate, "coordinate");
        Objects.requireNonNull(action, "action");
        return locks.runLocked("@world-coordinate:" + coordinate.storageKey(), LOCK_TIMEOUT_MS, action);
    }

    private static <T> T runCoordinateLocks(PlayerLock locks, List<Coord> coordinates, Supplier<T> action) {
        TreeSet<String> keys = new TreeSet<>();
        for (Coord coordinate : coordinates) keys.add(coordinate.storageKey());
        return lockCoordinate(locks, new ArrayList<>(keys), 0, action);
    }

    private static <T> T lockCoordinate(PlayerLock locks, List<String> keys, int index, Supplier<T> action) {
        if (index == keys.size()) return action.get();
        return locks.runLocked("@world-coordinate:" + keys.get(index), LOCK_TIMEOUT_MS,
                () -> lockCoordinate(locks, keys, index + 1, action));
    }
}
