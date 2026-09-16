package com.ironoath.core.player;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import com.ironoath.common.time.DayKey;

/**
 * 职责：付费权益在<b>玩家存档</b>上的那几位状态（B19 §一.1「三类商品各自是一套系统」的落点）。
 * 依赖：无（纯数据，随 {@link PlayerSave} 一起持久化）。
 *
 * <p><b>为什么住在 PlayerSave 而不是单开一个 PaidStore</b>：这几位的写入方全都是「读存档 → 改 → 写档」
 * 那一族（{@code PlayerWallet} 已经是同一个形状），单开一个端口要多四处注册
 * （存储端口清单、Mongo 装配、索引、守卫测试）却仍然要靠同一把玩家锁串行化。放存档上还顺带白拿乐观锁。
 *
 * <p><b>为什么不存「有没有买过基金」的布尔而存时刻</b>：布尔回答不了「什么时候买的」，
 * 而客服处理「我上周买的基金怎么没到账」时要的正是那个时刻。时刻非空即等价于「买过」。
 *
 * <p><b>月卡的队列 +1 与免广告不在这里占位</b>：那是从 {@link #cardExpireAt} 推出来的<b>读时派生值</b>，
 * 落库就成了第二真相 —— 到期那一刻没人会去把那一格收走（服务端不跑定时器，B00 陷阱 2），
 * 于是「已到期还多一格」会永久留在存档里。
 *
 * @param cardExpireAt 月卡到期时刻（毫秒）；null = 从未买过。续费在原到期时刻上叠加，所以它可以大于「购买时刻 + 一期」
 * @param cardClaimedThroughAt <b>日包结清游标</b>：该时刻所属自然日（含）之前的日包都已结清；null = 从未结算过。
 *                             存毫秒而不是 {@code yyyyMMdd} 字符串：与 {@code DayKey} 的
 *                             {@code daysBetween} / {@code startOfDayPlusDays} 直接同域运算，
 *                             不必把字符串再解回日期（解错一次格式就少算或多数一天）。
 *                             购卡时把它种成「昨天的 0 点」，于是"今天该发几天"这条算术从头到尾不需要知道购卡日
 * @param fundPurchasedAt 成长基金购买时刻；null = 没买过（买过即永久，基金不设过期）
 * @param fundClaimedTiers 已领掉的基金档位，存的是 {@code product_reward} 的行 id（不是序号：
 *                         表里插一行会让序号整体错位，而错位的后果是玩家领到别档的钱）
 * @param firstChargedAt 首充时刻；null = 还没有首充。<b>这一位同时是「首充只发一次」的凭据</b>
 * @param fulfilledOrderIds 已发过货的订单号（有界，见 {@link #FULFILL_LEDGER_MAX}）。
 *                          B19 §一.1 的「幂等键 = orderId，实现自审」就落在这里：
 *                          订单状态机只挡状态迁移，挡不住补单把同一份奖励发两遍
 */
public record PlayerPaid(Long cardExpireAt, Long cardClaimedThroughAt, Long fundPurchasedAt,
                         Set<String> fundClaimedTiers, Long firstChargedAt,
                         Set<String> fulfilledOrderIds) {

    /**
     * 发货幂等账本最多记几笔。
     *
     * <p>补单窗口只有一笔订单重试几次那么长（{@code PAY_FULFILL_MAX_ATTEMPTS}），
     * 32 笔远大于它；不封顶的话这就是一条随付费次数无限长大的存档字段。
     */
    public static final int FULFILL_LEDGER_MAX = 32;

    public PlayerPaid {
        // 刻意不用 Set.copyOf：它不保证迭代顺序，而 withFulfilledOrder 的"丢最早几笔"
        // 靠的正是插入顺序 —— 换成 copyOf 之后被丢掉的会是随机几笔，一条老订单于是可能重发两份货。
        fundClaimedTiers = immutableOrdered(fundClaimedTiers);
        fulfilledOrderIds = immutableOrdered(fulfilledOrderIds);
        requirePositiveOrNull(cardExpireAt, "月卡到期时刻", "从未买过");
        requirePositiveOrNull(cardClaimedThroughAt, "日包结清游标", "从未领过");
        requirePositiveOrNull(fundPurchasedAt, "基金购买时刻", "未购买");
        requirePositiveOrNull(firstChargedAt, "首充时刻", "未首充");
        requireNoBlank(fundClaimedTiers, "基金档位");
        requireNoBlank(fulfilledOrderIds, "订单号");
    }

    /** 从未付过任何权益类商品 —— 老存档读成它（与 {@link PlayerGuide#empty()} 同一条读法）。 */
    public static PlayerPaid empty() {
        return new PlayerPaid(null, null, null, Set.of(), null, Set.of());
    }

    /** 月卡当前是否在有效期内。到期判定只看服务端时间，客户端时钟不参与。 */
    public boolean cardActive(long now) {
        return cardExpireAt != null && cardExpireAt > now;
    }

    /**
     * 今天的日包是否已经结清过。
     *
     * <p>比较的是<b>自然日</b>而不是时刻：游标落在今天的 0 点，而 {@code now} 可能是今天 23:59，
     * 直接比大小会把同一天判成没领过（于是当天可以无限领）。
     */
    public boolean cardClaimedToday(long now) {
        return cardClaimedThroughAt != null
                && DayKey.of(cardClaimedThroughAt).equals(DayKey.of(now));
    }

    /** 买过成长基金。 */
    public boolean fundPurchased() {
        return fundPurchasedAt != null;
    }

    /** 该档基金是否已领掉。 */
    public boolean fundTierClaimed(String tierId) {
        return fundClaimedTiers.contains(tierId);
    }

    /** 这个账号是否已经有过首充（限购与「双倍只翻一次」都读这一位）。 */
    public boolean firstCharged() {
        return firstChargedAt != null;
    }

    /** 这笔订单的货是否已经发过 —— 补单重复调用同一订单时靠它挡第二遍。 */
    public boolean fulfilled(String orderId) {
        return orderId != null && fulfilledOrderIds.contains(orderId);
    }

    /**
     * 续一期月卡：未到期则在原到期时刻上叠加，已过期（或从未买过）则从 {@code now} 起算。
     *
     * <p>B19 §五②c：不设续期上限。设上限会造出「付了钱什么也没多」的订单，那是一笔收错了的钱。
     *
     * <p><b>游标为 null 时种成「昨天的 0 点」</b>：意思是"昨天之前没有任何一天欠着你"。
     * 有了这个起点，「今天该发几天」= 从游标所在自然日到今日的自然日数，
     * 不需要再存一个购买日期 —— 少存一位，也就少一处会和到期时刻打架的数。
     * 游标非空时一律不动：续期不该把已经攒下的漏领天数清掉。
     */
    public PlayerPaid withCardExtended(long now, long durationMillis) {
        if (durationMillis <= 0L) {
            throw new IllegalArgumentException("一期时长必须为正毫秒数，实际=" + durationMillis);
        }
        long base = cardExpireAt != null && cardExpireAt > now ? cardExpireAt : now;
        Long cursor = cardClaimedThroughAt != null
                ? cardClaimedThroughAt : DayKey.startOfDayPlusDays(now, -1);
        return new PlayerPaid(base + durationMillis, cursor, fundPurchasedAt,
                fundClaimedTiers, firstChargedAt, fulfilledOrderIds);
    }

    /** 把日包结清到某个自然日（传 {@code DayKey.startOfDayPlusDays(now, 0)} 即"结到今天")。 */
    public PlayerPaid withCardSettledThrough(long dayStartMillis) {
        return new PlayerPaid(cardExpireAt, dayStartMillis, fundPurchasedAt,
                fundClaimedTiers, firstChargedAt, fulfilledOrderIds);
    }

    /** 登记买过基金；重复登记返回自身，好让「再买一次基金」不会改写第一笔的时刻。 */
    public PlayerPaid withFundPurchased(long now) {
        if (fundPurchasedAt != null) {
            return this;
        }
        return new PlayerPaid(cardExpireAt, cardClaimedThroughAt, now,
                fundClaimedTiers, firstChargedAt, fulfilledOrderIds);
    }

    /** 记一档基金已领。调用方必须先用 {@link #fundTierClaimed} 挡过重复。 */
    public PlayerPaid withFundTierClaimed(String tierId) {
        Set<String> next = new LinkedHashSet<>(fundClaimedTiers);
        next.add(tierId);
        return new PlayerPaid(cardExpireAt, cardClaimedThroughAt, fundPurchasedAt,
                next, firstChargedAt, fulfilledOrderIds);
    }

    /** 记下首充时刻；已有首充时返回自身，于是「只发一次」在类型层面就是成立的。 */
    public PlayerPaid withFirstCharged(long now) {
        if (firstChargedAt != null) {
            return this;
        }
        return new PlayerPaid(cardExpireAt, cardClaimedThroughAt, fundPurchasedAt,
                fundClaimedTiers, now, fulfilledOrderIds);
    }

    /**
     * 记一笔订单已发货，并按 {@link #FULFILL_LEDGER_MAX} 丢掉最早的那些。
     *
     * <p>调用方必须先问过 {@link #fulfilled}：这个方法不做重复判断，判断的活留在调用点，
     * 好让「已经发过」那条路径能原样返回、不必在这里猜该发什么。
     */
    public PlayerPaid withFulfilledOrder(String orderId) {
        Set<String> next = new LinkedHashSet<>(fulfilledOrderIds);
        next.add(orderId);
        while (next.size() > FULFILL_LEDGER_MAX) {
            var oldest = next.iterator();
            oldest.next();
            oldest.remove();
        }
        return new PlayerPaid(cardExpireAt, cardClaimedThroughAt, fundPurchasedAt,
                fundClaimedTiers, firstChargedAt, next);
    }

    private static Set<String> immutableOrdered(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    /**
     * 时刻类字段只允许「正的毫秒时间戳」或「null」。
     *
     * <p>0 与负数一律拒：0 会被任何一处 {@code expireAt > now} 之外的读法当成"1970 年就买过"，
     * 那种读数不会报错，只会让玩家多领或少领，而它对不上任何一条日志。
     */
    private static void requirePositiveOrNull(Long value, String what, String nullMeans) {
        if (value != null && value <= 0L) {
            throw new IllegalArgumentException(what + "必须为正的服务端时间戳，或为 null 表示" + nullMeans);
        }
    }

    private static void requireNoBlank(Set<String> values, String what) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(what + "集合里不得有空白项");
            }
        }
    }
}
