package com.ironoath.core.pay;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.player.PlayerPaid;

/**
 * 职责：月卡日包「今天该发几天」这条算术（B19 §一.1a、§五②a）。
 * 依赖：{@link DayKey}（UTC+8 自然日）、{@link PlayerPaid}（只读它的两位）。
 *
 * <p><b>为什么单独一个类而不是写在 app service 里</b>：这条算术是"发多少钱"的判定，
 * 但它不需要数据库、不需要 Spring、也不需要配置表 —— 放在 core 才能把跨日、跨月、
 * 卡在到期日当天这些边界一次性测干净（{@code PaidEntitlementsTest}）。
 *
 * <p><b>两个上限叠在一起</b>：
 * <ul>
 *   <li><b>欠的</b>：结清游标所在自然日到今天，隔了几个自然日就欠几天（游标在购卡时被种成"昨天"，
 *       所以"昨天之前不欠"这个语义不需要额外存购买日）；</li>
 *   <li><b>还剩的</b>：今天到到期时刻之间还有几个自然日（含今天）。</li>
 * </ul>
 * 取小值就是 §五②a 那句「未领的天数在有效期内可累计补领，上限 = 剩余有效期」。
 * 少了第二个上限，玩家两周后回来能一次领走 14 份日包，"每日回来点一下"这条留存设计就没了；
 * 少了第一个上限，漏领三天就永久少领三天，那是收了钱不给货。
 *
 * <p><b>按自然日而不是按 24 小时</b>：日包是"每天领一次"的东西，玩家心里的"一天"是日历日。
 * 用 {@code 86400000} 除法会让"昨晚 23 点买的卡"在今晚 23 点之前只算一天，
 * 而日切时刻还会随购买时间漂移 —— 与全项目的日限次口径（{@link DayKey}）分叉。
 */
public final class PaidEntitlements {

    /** 今天点领取会得到什么。 */
    public enum CardVerdict {
        /** 可以领，天数见 {@link Claim#days()} */
        CLAIMABLE,
        /** 卡不在有效期内（从未买过、或已到期） */
        NOT_ACTIVE,
        /** 今天已经结清过，再点就是重复领取 */
        SETTLED_TODAY
    }

    /**
     * @param days {@link CardVerdict#CLAIMABLE} 时的应发天数（≥ 1），其余一律 0
     */
    public record Claim(CardVerdict status, long days) {

        public boolean claimable() {
            return status == CardVerdict.CLAIMABLE;
        }
    }

    private PaidEntitlements() {
    }

    /**
     * 判定今天应发几天。
     *
     * @param paid 玩家当前付费权益（null 当"什么都没买过"处理）
     * @param now  服务端当前时刻
     */
    public static Claim claimCard(PlayerPaid paid, long now) {
        if (paid == null || !paid.cardActive(now)) {
            return new Claim(CardVerdict.NOT_ACTIVE, 0L);
        }
        long owed = owedDays(paid, now);
        if (owed < 1L) {
            return new Claim(CardVerdict.SETTLED_TODAY, 0L);
        }
        // cardActive 已保证 expireAt > now ⇒ daysBetween(now, expireAt) >= 0 ⇒ 含今天至少剩 1 天
        long remaining = DayKey.daysBetween(now, paid.cardExpireAt()) + 1L;
        return new Claim(CardVerdict.CLAIMABLE, Math.min(owed, remaining));
    }

    /** 只读展示用的"现在点一下会领几天"，与领取动作共用同一条算术（不然界面和实发会打架）。 */
    public static long claimableDays(PlayerPaid paid, long now) {
        return claimCard(paid, now).days();
    }

    /**
     * 结清游标到今天隔了几个自然日。
     *
     * <p>游标为 null 只可能出现在"卡是 B19 之前种下的"这种不该存在的数据上，
     * 此时按 1 天处理：宁可少发一天，也不要把一张不知道买了多久的卡按最大额发出去。
     */
    private static long owedDays(PlayerPaid paid, long now) {
        Long cursor = paid.cardClaimedThroughAt();
        return cursor == null ? 1L : DayKey.daysBetween(cursor, now);
    }
}
