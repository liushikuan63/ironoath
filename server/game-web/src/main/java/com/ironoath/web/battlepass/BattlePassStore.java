package com.ironoath.web.battlepass;

import com.ironoath.web.dto.generated.BattlePassTrack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 职责：赛季战令进度的存储端口（一季一人一条）。
 * 依赖：生成的 {@link BattlePassTrack}（FREE / PAID 两个取值）。
 *
 * <p><b>为什么按赛季分账而不是往 {@code PlayerSave} 里加字段</b>：战令进度是"这一季的"，
 * 而赛季一换就必须归零 —— 把积分写进主存档意味着每个赛季都要有人记得去清它，
 * 而"忘了清"的症状是玩家在新赛季第一天就领满了 20 档。键里带 seasonId 之后，
 * 换赛季＝换一条记录，**清理这件事根本不存在**（与 B14 §4「赛季数据不混入主存档」同一条纪律）。
 *
 * <p><b>为什么是 {@link #update} 而不是 save</b>：领一次奖要同时做三件事
 * （校验达成 / 校验没领过 / 标记已领 + 发奖），而三件事必须落在同一个原子的读-改-写里 ——
 * 分成两次读写时，两个并发的领取请求会双双通过"没领过"的校验，同一档发两份奖励。
 * 与 {@code SeasonLedgerStore} 的取舍同源：宁可"标记了但没发出去"（有补偿与客服），
 * 也不能"发了两次"（没有任何补偿能追回）。
 */
public interface BattlePassStore {

    /**
     * 一季一人的战令进度。
     *
     * @param points  本赛季累计积分（只增不减：积分来源是任务与活动领取，没有扣分这件事）
     * @param claimed 已领过的档位键，格式 {@code tier:TRACK}（如 {@code 12:FREE}）。
     *                **两条线各记各的**：合成一位会让"领了免费那份"顺手把付费那份也标成已领
     */
    record Progress(long points, boolean paidUnlocked, Set<String> claimed) {

        public Progress {
            if (points < 0L) {
                throw new IllegalArgumentException("战令积分不得为负：" + points);
            }
            claimed = Set.copyOf(claimed);
        }

        public static Progress empty() {
            return new Progress(0L, false, Set.of());
        }

        /** 这个档位这条线领过没有。 */
        public boolean claimed(int tier, BattlePassTrack track) {
            return claimed.contains(keyOf(tier, track));
        }

        public Progress withPoints(long delta) {
            return new Progress(points + delta, paidUnlocked, claimed);
        }

        public Progress withPaidUnlocked() {
            return new Progress(points, true, claimed);
        }

        public Progress withClaimed(int tier, BattlePassTrack track) {
            Set<String> next = new LinkedHashSet<>(claimed);
            next.add(keyOf(tier, track));
            return new Progress(points, paidUnlocked, next);
        }

        public static String keyOf(int tier, BattlePassTrack track) {
            return tier + ":" + track.name();
        }
    }

    /**
     * 这一季有过进度的所有人。**只给赛季结束的补发用**（它是唯一一处需要按赛季枚举玩家的地方）：
     * 走榜单枚举不到"打过战令但没上榜"的人，而正是他们最容易留下没领的档位。
     */
    java.util.List<String> playerIdsOf(String seasonId);

    /** 没有记录时返回 {@link Progress#empty()}（不是 null、也不是抛：没打过战令是一个正常状态）。 */
    Progress load(String seasonId, String playerId);

    /**
     * 原子地改一次进度。
     *
     * <p>{@code change} 的入参是**当前值**（无记录时为 {@link Progress#empty()}），返回要落库的新值；
     * 返回 {@code null} 或与当前值相等表示"这次不改"，实现可以据此跳过写库。
     * 并发时 {@code change} 可能被多次调用（乐观锁重试），所以它必须是纯函数。
     */
    Progress update(String seasonId, String playerId, UnaryOperator<Progress> change);
}
