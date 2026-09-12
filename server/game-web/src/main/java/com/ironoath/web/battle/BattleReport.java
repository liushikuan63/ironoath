package com.ironoath.web.battle;

import com.ironoath.battle.BattleResult;
import com.ironoath.battle.BattleType;

import java.util.List;

/**
 * 职责：一场战斗的可回放记录（B09 战报持久化）。
 * 依赖：game-battle 的 {@link BattleResult}（纯数据）。
 *
 * <p><b>存的是内核的原始战果，不是下发给客户端的视图</b>。
 * 视图（{@code BattleResultView}）是协议形状，会随客户端需要演进；
 * 而战报是「那一场到底发生了什么」的事实记录，它的形状由内核决定。
 * 存视图的后果是：协议一改，历史战报就读不出来了 ——
 * 而客服排查「玩家投诉某场战斗算错了」时，需要恰恰是历史那一场。
 *
 * <p><b>凭 result.seed() + 双方构成可以 100% 复算</b>（铁律 4）。所以战报不需要存下每一个细节，
 * 但仍然存了完整的 {@code BattleResult}：复算要求调用方重新组装 BattleInput
 * （兵种属性、武将快照、规则参数），而规则参数会随配置热更变化 ——
 * 用今天的参数复算上个月的战斗，得到的可能不是同一场。存结果才是可靠的。
 *
 * @param reportId       战报 id
 * @param ownerId        这份战报归谁（列表按它查）。PVP 时攻守双方各存一份，ownerId 不同
 * @param attackerId     攻方 id
 * @param defenderId     守方 id；打野时是 mapmonster 的行 id
 * @param defenderName   守方显示名，由服务端下发（客户端不得自行拼接，见协议注释）
 * @param battleType     战斗类型
 * @param attackerHeroIds 攻方上阵武将，<b>按站位顺序</b>。用于判定技能是谁触发的
 * @param defenderHeroIds 守方上阵武将；野怪没有武将，为空列表
 * @param result         内核的原始战果
 * @param createdAt      生成时刻
 * @param expiresAt      过期时刻，来自 global.BATTLE_REPORT_TTL_SECONDS
 */
public record BattleReport(String reportId,
                           String ownerId,
                           String attackerId,
                           String defenderId,
                           String defenderName,
                           String attackerName,
                           BattleType battleType,
                           List<String> attackerHeroIds,
                           List<String> defenderHeroIds,
                           BattleResult result,
                           long createdAt,
                           long expiresAt) {

    public BattleReport {
        requireText(reportId, "reportId");
        requireText(ownerId, "ownerId");
        requireText(attackerId, "attackerId");
        if (battleType == null) {
            throw new IllegalArgumentException("battleType 不得为 null");
        }
        if (result == null) {
            throw new IllegalArgumentException("result 不得为 null");
        }
        attackerHeroIds = attackerHeroIds == null ? List.of() : List.copyOf(attackerHeroIds);
        defenderHeroIds = defenderHeroIds == null ? List.of() : List.copyOf(defenderHeroIds);
        if (createdAt <= 0L) {
            throw new IllegalArgumentException("createdAt 必须为正的服务端时间戳，实际=" + createdAt);
        }
        if (expiresAt < createdAt) {
            throw new IllegalArgumentException("expiresAt 不得早于 createdAt：expires=" + expiresAt
                    + ", created=" + createdAt);
        }
    }

    /** 是否已过期。过期的战报不下发详情，只等惰性清理。 */
    public boolean expired(long now) {
        return now >= expiresAt;
    }

    /** 我方（ownerId）是否获胜。列表页的「胜/败」标签用它，不让客户端自己推断。 */
    public boolean won() {
        return switch (result.winner()) {
            case ATTACKER -> ownerId.equals(attackerId);
            case DEFENDER -> ownerId.equals(defenderId);
            case DRAW -> false;
        };
    }

    /**
     * 对手 id，以战报主人（ownerId）为视角。
     *
     * <p><b>必须与 {@link #won()} 同一条口径</b>：won() 一直按 ownerId 判断，
     * 而对手此前恒取 defenderId，于是 PVP 守方那一份战报看到的对手是<b>自己</b>。
     * 攻守各存一份的全部意义正在于两个人各自看到对方 ——
     * 被打的人下线回来想知道的是「谁打的我」，不是「我被我自己打了」。
     */
    public String opponentId() {
        return ownerId.equals(attackerId) ? defenderId : attackerId;
    }

    /**
     * 对手显示名，以战报主人为视角。
     *
     * <p>主人是攻方时取守方名（PVE 就是野怪名，PVP 是对方城主的昵称）；
     * 主人是守方时取 {@code attackerName}。<b>缺失就抛，不回落到别的名字</b>：
     * 回落成守方名等于让被打的人看到「对手：我自己」，
     * 回落成空串则让客户端显示一个空白标题 —— 两种都比当场报错难查。
     */
    public String opponentName() {
        if (ownerId.equals(attackerId)) {
            return defenderName;
        }
        if (attackerName == null || attackerName.isBlank()) {
            throw new IllegalStateException("战报 " + reportId + " 的主人是守方，却没有 attackerName："
                    + "PVP 战报必须同时写入攻守双方的名字，否则守方看到的对手是自己");
        }
        return attackerName;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不得为空");
        }
    }
}
