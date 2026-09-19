package com.ironoath.web.store.mongo;

import com.ironoath.web.battlepass.BattlePassStore;
import org.springframework.data.annotation.Id;

import java.util.List;

/**
 * 职责：战令进度的 MongoDB 文档（一条 = 一季一人）。
 * 依赖：{@link BattlePassStore.Progress}。
 *
 * <p>{@code _id} 用 {@code seasonId:playerId}，与内存实现同一条拼法 —— 两侧对着读时不必做心算映射。
 *
 * <p><b>字段摊平而不是嵌一个 progress 对象</b>：乐观锁要坚持"读出来的那一版是谁"，
 * 而比较整个嵌套对象在 Mongo 上会退化成"字段顺序也要一致"的脆判据；
 * 摊平之后并发守卫只比 {@code version} 一个数（与社交存储的乐观锁同一条做法）。
 * 代价是 {@code MongoBattlePassStore} 的 {@code $set} 必须逐个列出字段 —— 那一份清单
 * 正是第 21 道门（{@code check-mongo-set-coverage}）盯着的东西：漏一个字段 =
 * 内存实现全绿、Mongo 上每次重读把那一列丢掉。
 */
public record BattlePassDocument(
        @Id String id,
        // mongo-save-exempt: 与 _id 同源、建文档那一刻就固定（键的一部分）。改它等于把这条记录
        // 搬到另一个赛季/玩家名下，而那件事的正确做法是删了重写 —— 所以它不进 $set 白名单。
        String seasonId,
        // mongo-save-exempt: 同上（seasonId:playerId 就是 _id）。留这两列是为了按玩家/赛季查得动。
        String playerId,
        long points,
        boolean paidUnlocked,
        List<String> claimed,
        long version) {

    /** 集合名。集中定义避免各处散落字符串。 */
    public static final String COLLECTION = "battle_pass";

    public static String keyOf(String seasonId, String playerId) {
        return seasonId + ":" + playerId;
    }

    static BattlePassDocument of(String seasonId, String playerId, BattlePassStore.Progress progress) {
        return new BattlePassDocument(keyOf(seasonId, playerId), seasonId, playerId,
                progress.points(), progress.paidUnlocked(), List.copyOf(progress.claimed()), 0L);
    }

    BattlePassStore.Progress progress() {
        return new BattlePassStore.Progress(points, paidUnlocked, java.util.Set.copyOf(claimed));
    }
}
