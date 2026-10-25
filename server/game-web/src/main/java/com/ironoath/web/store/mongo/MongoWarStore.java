package com.ironoath.web.store.mongo;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

import com.ironoath.core.nation.WarScoreBoard;
import com.ironoath.web.nation.WarRulesAssembler;
import com.ironoath.web.nation.WarStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * 国战战事的 Mongo 实现。读返回绑定仓储版本的副本，同一档的更新均做 CAS。
 * 疲劳、March 回执与其余业务状态在同一次整档更新中保存，不在数据库层计算积分。
 * 开战的「没有活跃场再插入」仍只受本实例锁保护，跨实例开出不同场的限制另行处理。
 */
public final class MongoWarStore implements WarStore {

    private static final int MAX_ATTEMPTS = 8;
    private final MongoTemplate mongo;
    private final WarRulesAssembler rules;
    private final Object activeLock = new Object();

    public MongoWarStore(MongoTemplate mongo, WarRulesAssembler rules) {
        this.mongo = Objects.requireNonNull(mongo, "mongo 不得为 null");
        this.rules = Objects.requireNonNull(rules, "rules 不得为 null");
    }

    @Override
    public boolean insertIfAbsent(WarScoreBoard board) {
        String warId = WarStore.documentIdOf(requireBoard(board));
        try {
            mongo.insert(WarDocument.fromDomain(warId, board), WarDocument.COLLECTION);
            board.bindRepositoryVersion(0L);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    @Override
    public boolean insertIfNoneActive(WarScoreBoard board) {
        requireBoard(board);
        synchronized (activeLock) {
            if (mongo.exists(Query.query(Criteria.where("state.phase")
                    .ne(WarScoreBoard.Phase.SETTLED.name())), WarDocument.COLLECTION)) {
                return false;
            }
            return insertIfAbsent(board);
        }
    }

    @Override
    public WarStore.FatigueResult addFatigue(String fatigueNationId, String playerId,
                                             long marches, long wounded) {
        if (marches <= 0L && wounded <= 0L) {
            return WarStore.FatigueResult.SKIPPED;
        }
        synchronized (activeLock) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                WarDocument doc = latestDocument();
                if (!isActive(doc)) {
                    return WarStore.FatigueResult.NO_ACTIVE_WAR;
                }
                WarScoreBoard board = toDomain(doc);
                WarStore.FatigueResult result =
                        WarStore.applyFatigue(board, fatigueNationId, playerId, marches, wounded);
                if (result != WarStore.FatigueResult.APPLIED || replace(doc, board)) {
                    return result;
                }
            }
            throw conflict();
        }
    }

    @Override
    public WarStore.FatigueResult addMarchFatigueOnce(String marchId, String fatigueNationId,
                                                     String playerId, long departedAt) {
        WarScoreBoard.MarchFatigueReceipt receipt =
                new WarScoreBoard.MarchFatigueReceipt(marchId, fatigueNationId, playerId, departedAt);
        synchronized (activeLock) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Query atDeparture = Query.query(Criteria.where("startedAt").lte(departedAt))
                        .with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1);
                WarDocument doc = mongo.findOne(atDeparture, WarDocument.class, WarDocument.COLLECTION);
                if (doc == null || doc.state() == null) {
                    return WarStore.FatigueResult.NO_ACTIVE_WAR;
                }
                WarScoreBoard board = toDomain(doc);
                boolean previouslyRecorded = board.marchFatigueReceipt(marchId) != null;
                WarStore.FatigueResult result = WarStore.applyMarchFatigue(board, receipt);
                if (result != WarStore.FatigueResult.APPLIED || previouslyRecorded) {
                    return result;
                }
                try {
                    if (replace(doc, board)) {
                        return WarStore.FatigueResult.APPLIED;
                    }
                } catch (RuntimeException unknownWriteResponse) {
                    // 写入可能成功而响应丢失：只读确认，不从旧副本再次做加法。
                    WarDocument persisted = mongo.findById(doc.warId(), WarDocument.class, WarDocument.COLLECTION);
                    if (persisted != null && persisted.state() != null
                            && receipt.equals(toDomain(persisted).marchFatigueReceipt(marchId))) {
                        return WarStore.FatigueResult.APPLIED;
                    }
                    throw unknownWriteResponse;
                }
            }
            throw conflict();
        }
    }

    @Override
    public WarStore.GoalClaimResult claimServerGoal(String playerId, long now) {
        synchronized (activeLock) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                WarDocument doc = latestDocument();
                WarScoreBoard board = doc == null || doc.state() == null ? null : toDomain(doc);
                WarStore.GoalClaimResult result = WarStore.applyGoalClaim(board, playerId, now);
                if (result != WarStore.GoalClaimResult.CLAIMED || replace(doc, board)) {
                    return result;
                }
            }
            throw conflict();
        }
    }

    @Override
    public void save(WarScoreBoard board) {
        String warId = WarStore.documentIdOf(requireBoard(board));
        long expectedVersion = board.repositoryVersion();
        if (expectedVersion < 0L) {
            throw new IllegalStateException("战事不存在或快照未从仓储读取，无法落盘：warId=" + warId
                    + "。建档请走 insertIfAbsent；已建档请重读后重试。");
        }
        WarDocument original = new WarDocument(warId, board.startedAt(), board.toSnapshot(), expectedVersion);
        if (!replace(original, board)) {
            throw new IllegalStateException("战事不存在或快照版本冲突，请重读后重试：warId=" + warId
                    + "。建档请走 insertIfAbsent。");
        }
    }

    @Override
    public Optional<WarScoreBoard> findLatest() {
        return Optional.ofNullable(latestDocument()).map(this::toDomain);
    }

    @Override public Optional<WarScoreBoard> findByStartedAt(long startedAt) {
        var doc=mongo.findById(WarStore.documentIdOf(startedAt),WarDocument.class,WarDocument.COLLECTION);
        if(doc==null)return Optional.empty();
        if(doc.state()==null || doc.startedAt()!=startedAt || doc.state().startedAt()!=startedAt)
            throw new IllegalStateException("原战事存档身份损坏");
        return Optional.of(toDomain(doc));
    }

    @Override public WarScoreBoard.BattleEvent recordBattleEvent(WarScoreBoard.BattleEvent event) {
        if(event==null)throw new IllegalArgumentException("原战斗事件不得为空");
        String id=WarStore.documentIdOf(event.warStartedAt());
        synchronized(activeLock) {
            for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
                var doc=mongo.findById(id,WarDocument.class,WarDocument.COLLECTION);
                if(doc==null || doc.state()==null)throw new IllegalStateException("原战事缺失，不能推定原战斗已记账");
                if(doc.startedAt()!=event.warStartedAt() || doc.state().startedAt()!=event.warStartedAt())throw new IllegalStateException("原战事存档身份损坏");
                var board=toDomain(doc);if(!board.applyBattleEvent(event))return event;
                try {if(replace(doc,board))return event;}
                catch(RuntimeException uncertain) {
                    try {
                        var persisted=findByStartedAt(event.warStartedAt());
                        if(persisted.isPresent() && event.equals(persisted.get().battleEvent(event.sourceId())))return event;
                    }catch(RuntimeException readFailure){uncertain.addSuppressed(readFailure);}
                    throw uncertain;
                }
            }
            throw conflict();
        }
    }

    @Override public void captureGateForBattle(WarScoreBoard.BattleReservation reservation,String winnerNationId,
            String expectedPreviousNationId) {
        if(reservation==null)throw new IllegalArgumentException("原War reservation缺失，不能记录关卡归属");
        String id=WarStore.documentIdOf(reservation.warStartedAt());
        synchronized(activeLock) {
            for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
                var doc=mongo.findById(id,WarDocument.class,WarDocument.COLLECTION);
                if(doc==null || doc.state()==null)throw new IllegalStateException("原War存档缺失，不能记录COMBAT关卡归属");
                if(doc.startedAt()!=reservation.warStartedAt() || doc.state().startedAt()!=reservation.warStartedAt())
                    throw new IllegalStateException("原War存档startedAt与COMBAT reservation不一致");
                var board=toDomain(doc);
                if(!WarStore.applyGateCaptureForBattle(board,reservation,winnerNationId,expectedPreviousNationId))return;
                try {if(replace(doc,board))return;}
                catch(RuntimeException uncertain) {
                    try {
                        var current=findByStartedAt(reservation.warStartedAt()).orElse(null);
                        if(current!=null && !WarStore.applyGateCaptureForBattle(current,reservation,
                                winnerNationId,expectedPreviousNationId))return;
                    } catch(RuntimeException readFailure) {if(readFailure!=uncertain)uncertain.addSuppressed(readFailure);}
                    throw uncertain;
                }
            }
            throw conflict();
        }
    }

    @Override public void captureCapitalForBattle(WarScoreBoard.BattleReservation reservation,String winnerNationId) {
        if(reservation==null)throw new IllegalArgumentException("原War reservation缺失，不能记录王城归属");
        String id=WarStore.documentIdOf(reservation.warStartedAt());
        synchronized(activeLock) {
            for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
                var doc=mongo.findById(id,WarDocument.class,WarDocument.COLLECTION);
                if(doc==null || doc.state()==null)throw new IllegalStateException("原War存档缺失，不能记录COMBAT王城归属");
                if(doc.startedAt()!=reservation.warStartedAt() || doc.state().startedAt()!=reservation.warStartedAt())
                    throw new IllegalStateException("原War存档startedAt与COMBAT reservation不一致");
                var board=toDomain(doc);
                if(!WarStore.applyCapitalCaptureForBattle(board,reservation,winnerNationId))return;
                try {if(replace(doc,board))return;}
                catch(RuntimeException uncertain) {
                    try {
                        var current=findByStartedAt(reservation.warStartedAt()).orElse(null);
                        if(current!=null && !WarStore.applyCapitalCaptureForBattle(current,reservation,winnerNationId))return;
                    } catch(RuntimeException readFailure) {if(readFailure!=uncertain)uncertain.addSuppressed(readFailure);}
                    throw uncertain;
                }
            }
            throw conflict();
        }
    }

    @Override public Optional<WarScoreBoard.BattleReservation> reserveBattle(String sourceId,long sequence,
            String operationId,long happenedAt,List<WarScoreBoard.BattleMember> members) {
        synchronized(activeLock) {
            for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
                WarDocument doc=latestDocument();if(doc==null || doc.state()==null)return Optional.empty();
                WarScoreBoard board=toDomain(doc);
                var old=board.pendingBattleReservation(operationId);
                var reservation=board.reserveBattle(sourceId,sequence,operationId,happenedAt,members);
                if(reservation.isEmpty() || old.isPresent())return reservation;
                try {if(replace(doc,board))return reservation;}
                catch(RuntimeException unknown) {
                    try {
                        var read=findByStartedAt(doc.startedAt());
                        var persisted=read.flatMap(b->b.pendingBattleReservation(operationId));
                        if(persisted.filter(reservation.get()::equals).isPresent())return reservation;
                    } catch(RuntimeException readFailure) {if(readFailure!=unknown)unknown.addSuppressed(readFailure);}
                    throw unknown;
                }
            }
            throw conflict();
        }
    }

    @Override public Optional<WarScoreBoard.BattleReservation> pendingBattleReservation(long warStartedAt,String operationId) {
        return findByStartedAt(warStartedAt).flatMap(board->board.pendingBattleReservation(operationId));
    }

    @Override public boolean completeBattleOperation(long warStartedAt,String operationId) {
        synchronized(activeLock) {
            String id=WarStore.documentIdOf(warStartedAt);
            for(int attempt=0;attempt<MAX_ATTEMPTS;attempt++) {
                WarDocument doc=mongo.findById(id,WarDocument.class,WarDocument.COLLECTION);
                if(doc==null || doc.state()==null)return false;
                WarScoreBoard board=toDomain(doc);
                var reservation=board.pendingBattleReservation(operationId);
                if(reservation.isEmpty())return board.hasBattleOperationReceipt(operationId);
                var sourceId=reservation.get().sourceId();var sequence=reservation.get().sequence();
                if(!board.completeBattleOperation(operationId))return false;
                try {if(replace(doc,board))return true;}
                catch(RuntimeException unknown) {
                    try {
                        var read=findByStartedAt(warStartedAt).orElse(null);
                        var event=read==null?null:read.battleEvent(sourceId);
                        if(read!=null && read.pendingBattleReservation(operationId).isEmpty() && event!=null
                                && event.sequence()==sequence && event.operationId().equals(operationId))return true;
                    } catch(RuntimeException readFailure) {if(readFailure!=unknown)unknown.addSuppressed(readFailure);}
                    throw unknown;
                }
            }
            throw conflict();
        }
    }

    @Override
    public Optional<WarStore.Settlement> settleIfExpired(long now) {
        synchronized (activeLock) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                WarDocument doc = latestDocument();
                if (doc == null || doc.state() == null) {
                    return Optional.empty();
                }
                WarScoreBoard board = toDomain(doc);
                if (!WarStore.dueToSettle(board, now)) {
                    return Optional.of(WarStore.Settlement.notSettled(board));
                }
                if(board.hasPendingBattleReservations())return Optional.of(WarStore.Settlement.notSettled(board));
                if (board.phase() == WarScoreBoard.Phase.PREPARATION) {
                    board.expirePreparation(now);
                    if (replace(doc, board)) {
                        return Optional.of(WarStore.Settlement.abandoned(board));
                    }
                    continue;
                }
                WarScoreBoard.Result result = board.settle(now);
                if (replace(doc, board)) {
                    return Optional.of(new WarStore.Settlement(board, true, result));
                }
            }
            throw conflict();
        }
    }

    @Override
    public WarStore.KillResult recordKills(String killerNationId, String killerPlayerId, long units, long now) {
        if (units <= 0L) {
            return WarStore.KillResult.SKIPPED;
        }
        synchronized (activeLock) {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                WarDocument doc = latestDocument();
                if (!isActive(doc)) {
                    return WarStore.KillResult.NO_ACTIVE_WAR;
                }
                WarScoreBoard board = toDomain(doc);
                if (WarStore.dueToSettle(board, now)) {
                    return WarStore.KillResult.EXPIRED;
                }
                WarStore.KillResult result =
                        WarStore.applyKills(board, killerNationId, killerPlayerId, units);
                if (replace(doc, board)) {
                    return result;
                }
            }
            throw conflict();
        }
    }

    private WarDocument latestDocument() {
        Query query = new Query().with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1);
        return mongo.findOne(query, WarDocument.class, WarDocument.COLLECTION);
    }

    @Override
    public Optional<WarScoreBoard> findLatestBetween(String nationA, String nationB) {
        Query query = new Query(new Criteria().andOperator(
                        Criteria.where("state.nations.nationId").is(nationA),
                        Criteria.where("state.nations.nationId").is(nationB)))
                .with(Sort.by(Sort.Direction.DESC, "startedAt")).limit(1);
        return Optional.ofNullable(mongo.findOne(query, WarDocument.class, WarDocument.COLLECTION))
                .map(this::toDomain);
    }

    @Override
    public void clear() {
        mongo.remove(new Query(), WarDocument.COLLECTION);
    }

    /** 旧文档没有 version，按零版本 CAS 并在首次更新补齐；所有 state 写口都走这里。 */
    private boolean replace(WarDocument original, WarScoreBoard board) {
        Criteria version = original.version() == 0L
                ? new Criteria().orOperator(Criteria.where("version").is(0L),
                        Criteria.where("version").exists(false))
                : Criteria.where("version").is(original.version());
        Query expected = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(original.warId()), version));
        Update next = new Update()
                .set("startedAt", board.startedAt())
                .set("state", board.toSnapshot())
                .set("version", original.version() + 1L);
        var result = mongo.updateFirst(expected, next, WarDocument.class, WarDocument.COLLECTION);
        if (result.getMatchedCount() == 0L) {
            return false;
        }
        board.bindRepositoryVersion(original.version() + 1L);
        return true;
    }

    private WarScoreBoard toDomain(WarDocument doc) {
        WarScoreBoard board = WarScoreBoard.fromSnapshot(doc.state(), rules.rules());
        board.bindRepositoryVersion(doc.version());
        return board;
    }

    private static boolean isActive(WarDocument doc) {
        return doc != null && doc.state() != null && doc.state().phase() != WarScoreBoard.Phase.SETTLED;
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("战事写入持续冲突，请重读后重试并保留出发恢复记录");
    }

    private static WarScoreBoard requireBoard(WarScoreBoard board) {
        if (board == null) {
            throw new IllegalArgumentException("board 不得为 null：没有板子就没有可落盘的状态");
        }
        return board;
    }
}
