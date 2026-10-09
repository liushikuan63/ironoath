package com.ironoath.web.social;

import java.util.LinkedHashMap;
import java.util.Map;
import com.ironoath.core.army.ArmyRepository;
import com.ironoath.core.army.ArmyState;
import com.ironoath.core.social.Rally;
import com.ironoath.web.social.SocialStore.RallySettlement;

/**
 * 可恢复的跨文档结清流程，调用方须持国家锁。它不是多文档事务：失败时日志留下未完工作。
 * 重启后先重放日志，再处理新的组织动作；退款凭据和兵力在同一 Army CAS 内写入。
 */
public final class RallySettlementRecovery {
    private RallySettlementRecovery() { }

    public static void recover(SocialStore store, ArmyRepository armies, String groupId) {
        for (RallySettlement settlement : store.pendingRallySettlementsOf(groupId)) {
            finish(store, armies, settlement);
        }
    }

    public static Rally settle(SocialStore store, ArmyRepository armies, Rally rally,
                               String quitterId, boolean abortDeparture) {
        Rally.Status required = abortDeparture ? Rally.Status.DEPARTED : Rally.Status.PREPARING;
        if (rally.status() != required) {
            throw new IllegalStateException("结清只能由 " + required + " 状态开始，实际=" + rally.status());
        }
        boolean all = quitterId == null || rally.initiatorId().equals(quitterId);
        Map<String, Map<String, Long>> refunds = new LinkedHashMap<>();
        if (all) {
            for (String member : rally.memberIds()) {
                refunds.put(member, rally.participant(member).troops());
            }
        } else if (rally.participant(quitterId) != null) {
            refunds.put(quitterId, rally.participant(quitterId).troops());
        } else {
            return rally;
        }
        String key = rally.rallyId() + ":" + rally.version() + ":"
                + (abortDeparture ? "abort" : all ? "cancel" : "quit:" + quitterId);
        RallySettlement settlement = store.putRallySettlementIfAbsent(new RallySettlement(key,
                rally.rallyId(), rally.groupId(), all ? null : quitterId, abortDeparture, refunds));
        finish(store, armies, settlement);
        return store.rallyOf(rally.rallyId()).orElseThrow();
    }

    private static void finish(SocialStore store, ArmyRepository armies, RallySettlement settlement) {
        transition(store, settlement);
        for (var refund : settlement.refunds().entrySet()) {
            refundOnce(armies, refund.getKey(), settlement.settlementId(), refund.getValue());
        }
        store.removeRallySettlement(settlement.settlementId());
    }

    private static boolean transitioned(Rally rally, RallySettlement settlement) {
        return settlement.quitterId() == null ? rally.status() == Rally.Status.CANCELLED
                : rally.status() == Rally.Status.PREPARING
                        && rally.participant(settlement.quitterId()) == null;
    }

    private static void transition(SocialStore store, RallySettlement settlement) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Rally rally = store.rallyOf(settlement.rallyId()).orElseThrow(() ->
                    new IllegalStateException("结清日志所指集结不存在：" + settlement.rallyId()));
            if (transitioned(rally, settlement)) { return; }
            long version = rally.version();
            if (settlement.abortDeparture()) {
                rally.abortDeparted();
            } else if (settlement.quitterId() == null) {
                rally.cancel(rally.initiatorId());
            } else {
                rally.quit(settlement.quitterId());
            }
            try {
                store.saveRally(rally, version);
                return;
            } catch (RuntimeException failure) {
                // 响应丢失也可能已提交，不能当作没写；未提交则下趟重读版本。
                Rally persisted = store.rallyOf(settlement.rallyId()).orElseThrow();
                if (transitioned(persisted, settlement)) { return; }
                if (attempt == 1) { throw failure; }
            }
        }
    }

    /** 单玩家返兵：兵力与独立业务凭据同档提交，可在取消、退出或返家重放中复用。 */
    public static void refundOnce(ArmyRepository armies, String playerId, String settlementId,
                                   Map<String, Long> troops) {
        armies.insertIfAbsent(playerId, new ArmyState());
        for (int attempt = 0; attempt < 2; attempt++) {
            // 版本必须先读。若状态读取期间又被改动，CAS 会拒绝旧版本；
            // 状态→版本顺序会把旧快照配上新版本，从而覆盖其他玩家请求。
            long version = armies.versionOf(playerId);
            ArmyState army = armies.findByPlayerId(playerId).orElseThrow();
            if (army.hasRallyRefund(settlementId)) { return; }
            army.refundRallyOnce(settlementId, troops);
            try {
                armies.save(playerId, army, version);
                return;
            } catch (RuntimeException failure) {
                if (armies.findByPlayerId(playerId).orElseThrow().hasRallyRefund(settlementId)) {
                    return;
                }
                if (attempt == 1) { throw failure; }
            }
        }
    }
}
