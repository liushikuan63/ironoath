package com.ironoath.core.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ironoath.common.time.DayKey;
import com.ironoath.core.player.PlayerPaid;

/**
 * 职责：月卡日包「今天该发几天」这条算术的单测（B19 验收 1 与验收 5 的算术部分）。
 * 依赖：{@link PaidEntitlements}、{@link PlayerPaid}；不需要 Spring、不需要库。
 *
 * <p><b>为什么单独一层测它</b>：这条规则的两个上限（欠的 / 还剩的）只在边界上才分得出谁生效，
 * 走 HTTP 要跨日就得拨全服时钟，一拨就影响同一个上下文里所有别的用例
 * （{@code PayEndpointTest} 为这件事写过说明）。放在 core 里，日界可以随便摆。
 */
class PaidEntitlementsTest {

    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private static final long MINUTE = TimeUnit.MINUTES.toMillis(1);
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    /** 2023-11-15 06:13:20（UTC+8）—— 与支付那组测试同一个基准。 */
    private static final long BASE = 1_700_000_000_000L;

    /** 一张第 day 天 0 点起、共 durationDays 天的卡，游标按购卡时的种法（昨天的 0 点）。 */
    private static PlayerPaid freshCard(long boughtAt, long durationDays) {
        return PlayerPaid.empty()
                .withCardExtended(boughtAt, TimeUnit.DAYS.toMillis(durationDays));
    }

    @Test
    @DisplayName("从未买过：不可领，且原因要说清是「没这张卡」而不是「今天领过了」")
    void neverPurchasedIsNotActive() {
        assertThat(PaidEntitlements.claimCard(PlayerPaid.empty(), BASE).status())
                .isEqualTo(PaidEntitlements.CardVerdict.NOT_ACTIVE);
        assertThat(PaidEntitlements.claimCard(null, BASE).status())
                .as("null 与「什么都没买过」同一条读法，不能让调用方自己判空")
                .isEqualTo(PaidEntitlements.CardVerdict.NOT_ACTIVE);
    }

    @Test
    @DisplayName("到期之后：不可领。到期时刻等于此刻也不算（严格大于）")
    void expiredCardIsNotActive() {
        PlayerPaid expired = freshCard(BASE, 30);
        long justAfter = expired.cardExpireAt();
        assertThat(PaidEntitlements.claimCard(expired, justAfter - MINUTE).status())
                .as("到期前一刻仍然有效").isEqualTo(PaidEntitlements.CardVerdict.CLAIMABLE);
        assertThat(PaidEntitlements.claimCard(expired, justAfter).status())
                .as("到期那一刻起就没权益了").isEqualTo(PaidEntitlements.CardVerdict.NOT_ACTIVE);
        assertThat(PaidEntitlements.claimCard(expired, justAfter + DAY).status())
                .isEqualTo(PaidEntitlements.CardVerdict.NOT_ACTIVE);
    }

    @Test
    @DisplayName("买卡当天第一次点：只发一天（漏领的算术从「购买日的下一个自然日」开始才不为负）")
    void firstClaimBuysTodayOnly() {
        PlayerPaid bought = freshCard(BASE, 30);
        var verdict = PaidEntitlements.claimCard(bought, BASE + HOUR);
        assertThat(verdict.status()).isEqualTo(PaidEntitlements.CardVerdict.CLAIMABLE);
        assertThat(verdict.days()).as("种下的游标是昨天的 0 点 ⇒ 今天欠 1 天").isEqualTo(1L);
    }

    @Test
    @DisplayName("同一天再点：SETTLED_TODAY。这条挡的是「当天无限领」，少判一次就是日包无限领")
    void secondClaimSameDayIsRejected() {
        PlayerPaid bought = freshCard(BASE, 30)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(BASE + HOUR, 0));
        assertThat(PaidEntitlements.claimCard(bought, BASE + 2 * HOUR).status())
                .as("同一天内换了时刻也不算新的一天")
                .isEqualTo(PaidEntitlements.CardVerdict.SETTLED_TODAY);
        assertThat(PaidEntitlements.claimableDays(bought, BASE + 2 * HOUR)).isZero();
    }

    @Test
    @DisplayName("隔天领：把漏掉的那天一起补上（§五②a「未领的天数在有效期内可累计补领」）")
    void missedDaysAreMadeUp() {
        PlayerPaid bought = freshCard(BASE, 30)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(BASE, 0));
        long twoDaysLater = DayKey.startOfDayPlusDays(BASE, 2) + HOUR;
        var verdict = PaidEntitlements.claimCard(bought, twoDaysLater);
        assertThat(verdict.status()).isEqualTo(PaidEntitlements.CardVerdict.CLAIMABLE);
        assertThat(verdict.days()).as("第 2 天没来、第 3 天来了 ⇒ 补一天 + 今天一天").isEqualTo(2L);
    }

    @Test
    @DisplayName("补领的上限是剩余有效期：三周没来的人拿不到 21 份，只能拿到卡剩下的那几天")
    void makeUpIsCappedByRemainingValidity() {
        PlayerPaid bought = freshCard(BASE, 30);
        long day25 = DayKey.startOfDayPlusDays(BASE, 24) + HOUR;
        var verdict = PaidEntitlements.claimCard(bought, day25);
        assertThat(verdict.days())
                .as("欠 25 天，但卡只剩 7 天（12-09 到 12-15，含今天）⇒ 只发 7 天。"
                        + "没有这个上限，两周后回来一次领完就能把留存设计整个绕过去")
                .isEqualTo(7L);
    }

    @Test
    @DisplayName("到期日当天仍然能领，且只剩这一天")
    void lastDayIsClaimableAlone() {
        PlayerPaid bought = freshCard(BASE, 30)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(BASE, 28) - DAY);
        long lastDay = bought.cardExpireAt() - HOUR;
        var verdict = PaidEntitlements.claimCard(bought, lastDay);
        assertThat(verdict.status()).isEqualTo(PaidEntitlements.CardVerdict.CLAIMABLE);
        assertThat(verdict.days()).isEqualTo(1L);
    }

    @Test
    @DisplayName("日界按 UTC+8 而不是 UTC：北京 00:30 与 23:30 是两个自然日，差 23 小时也是一天")
    void dayBoundaryIsUtcPlus8NotUtc() {
        // UTC+8 的 23:30 = UTC 15:30；跨过北京零点（UTC 16:00）只过了 30 分钟，但已经是新的一天
        long beforeMidnight = DayKey.startOfDayPlusDays(BASE, 1) + DAY - 30 * MINUTE;
        long afterMidnight = beforeMidnight + HOUR;
        assertThat(DayKey.of(beforeMidnight)).as("两个时刻的日键必须不同，否则这条用例什么都没测")
                .isNotEqualTo(DayKey.of(afterMidnight));

        PlayerPaid claimedYesterdayEvening = freshCard(BASE, 30)
                .withCardSettledThrough(DayKey.startOfDayPlusDays(beforeMidnight, 0));
        var verdict = PaidEntitlements.claimCard(claimedYesterdayEvening, afterMidnight);
        assertThat(verdict.status()).as("跨了北京的零点就是跨了一天，哪怕只隔一小时")
                .isEqualTo(PaidEntitlements.CardVerdict.CLAIMABLE);
        assertThat(verdict.days()).isEqualTo(1L);
    }

    @Test
    @DisplayName("续期不追回已经结清的天数，也不把到期时刻往回拉")
    void renewalKeepsCursorAndExtendsFromCurrentExpiry() {
        PlayerPaid bought = freshCard(BASE, 30);
        long claimedAt = DayKey.startOfDayPlusDays(BASE, 10) + HOUR;
        // 结清到"续期日的前一天"：于是续期日当天仍然欠着一天的日包
        PlayerPaid claimed = bought.withCardSettledThrough(DayKey.startOfDayPlusDays(claimedAt, -1));
        PlayerPaid renewed = claimed.withCardExtended(claimedAt, 30 * DAY);

        assertThat(renewed.cardExpireAt())
                .as("未到期再买 = 原到期时刻 + 30 天（§五②c），不是从「今天」重算")
                .isEqualTo(bought.cardExpireAt() + 30 * DAY);
        assertThat(renewed.cardClaimedThroughAt())
                .as("续期不该把已经攒下的漏领天数清掉")
                .isEqualTo(claimed.cardClaimedThroughAt());
        assertThat(PaidEntitlements.claimCard(renewed, claimedAt).days())
                .as("续期当天仍然能领这一天的")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("游标缺失的脏数据按「只欠今天」处理：宁可少发一天，也不按最大额发一张不知道买了多久的卡")
    void missingCursorFallsBackToTodayOnly() {
        PlayerPaid dirty = new PlayerPaid(BASE + 20 * DAY, null, null,
                java.util.Set.of(), null, java.util.Set.of());
        var verdict = PaidEntitlements.claimCard(dirty, BASE + HOUR);
        assertThat(verdict.days()).isEqualTo(1L);
        assertThat(PaidEntitlements.claimableDays(dirty, BASE + HOUR)).isEqualTo(1L);
    }

    @Test
    @DisplayName("展示用的 claimableDays 与领取用的 claimCard 是同一条算术，不是各写一遍的两个版本")
    void readOnlyProjectionSharesTheSameMath() {
        PlayerPaid bought = freshCard(BASE, 30);
        for (long offset : new long[]{HOUR, 5 * DAY, 29 * DAY, 35 * DAY}) {
            long at = BASE + offset;
            assertThat(PaidEntitlements.claimableDays(bought, at))
                    .isEqualTo(PaidEntitlements.claimCard(bought, at).days());
        }
    }

    @Test
    @DisplayName("负数或零的时长与到期时刻一律当场拒，不留到玩家领的时候才发现")
    void guardsRejectNonsense() {
        assertThatThrownBy(() -> PlayerPaid.empty().withCardExtended(BASE, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlayerPaid.empty().withCardExtended(BASE, -DAY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerPaid(0L, null, null, java.util.Set.of(), null,
                java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("月卡到期时刻");
        assertThatThrownBy(() -> new PlayerPaid(BASE, -1L, null, java.util.Set.of(), null,
                java.util.Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日包结清游标");
    }
}
