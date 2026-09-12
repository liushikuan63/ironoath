package com.ironoath.core.pay;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：付费弹窗频控（B15 §1，验收 6/7）与未成年限额的友好提示（§3、验收 4）。
 * 依赖：无（纯 Java，零框架）。
 *
 * <p><b>三条频控规则，缺一不可</b>：
 * <ol>
 *   <li><b>首次付费后 24 小时内不弹任何付费弹窗</b>（验收 6）。
 *       刚付过钱的人是最不该被打扰的：他已经在为这个游戏花钱了，
 *       再弹窗只会让他觉得「这游戏只想榨干我」，而那是退款与差评的来源</li>
 *   <li><b>同一礼包 24 小时最多 3 次</b>（验收 7）</li>
 *   <li><b>全局弹窗冷却 10 分钟</b>（验收 7）。这条是最容易被漏掉的：
 *       前两条都按礼包计，于是「五个不同礼包各弹一次」在五分钟内连着出现，
 *       每条规则都没违反，体验却是灾难</li>
 * </ol>
 *
 * <p><b>未成年限额是提示而不是拦截</b>（禁止项：不要让未成年限额提示变成硬拦截）。
 * 所以 {@link #minorPayNotice} 返回的是一段文案加一个「还能付多少」的数，
 * 而不是一个布尔值 —— 返回布尔值就等于只能表达「能/不能」，
 * 而合规要求的是「告诉他限额是多少、他还剩多少」，那是教育而不是拒绝。
 *
 * <p><b>Bot 不得出现在任何付费弹窗场景</b>（B11 §七 红线）。
 * 本类的 {@link #shouldShow} 接受一个 {@code viewerIsBot} 参数并<b>无条件拒绝</b>：
 * 参数存在的意义不是让调用方判断，而是让「忘了判断」这件事在编译期就报错。
 */
public final class PopupThrottle {

    /**
     * @param firstPayQuietMillis   首次付费后的静默期（毫秒）。来源 global.PAY_FIRST_PURCHASE_QUIET_HOURS
     * @param perGiftDailyMax       同一礼包 24h 内最多弹几次。来源 global.PAY_POPUP_PER_GIFT_DAILY_MAX
     * @param globalCooldownMillis  全局弹窗冷却（毫秒）。来源 global.PAY_POPUP_GLOBAL_COOLDOWN_MINUTES
     */
    public record Rules(long firstPayQuietMillis, int perGiftDailyMax, long globalCooldownMillis) {
        public Rules {
            if (firstPayQuietMillis < 0) {
                throw new IllegalArgumentException("静默期不得为负，实际=" + firstPayQuietMillis);
            }
            if (perGiftDailyMax < 1) {
                throw new IllegalArgumentException("perGiftDailyMax 必须 >= 1，否则礼包永远不会被弹出，实际="
                        + perGiftDailyMax);
            }
            if (globalCooldownMillis < 0) {
                throw new IllegalArgumentException("全局冷却不得为负，实际=" + globalCooldownMillis);
            }
        }
    }

    /**
     * 一次弹窗判定。
     *
     * @param allowed    是否允许弹
     * @param reason     不允许时的原因。<b>必须给</b>：弹窗被压制的理由如果不记录，
     *                   运营看到「礼包转化率是 0」时无法区分「没人想买」和「根本没弹出来」
     * @param retryAfterMillis 多久之后可以再试。0 表示本次压制与时间无关（例如 Bot）
     */
    public record Verdict(boolean allowed, String reason, long retryAfterMillis) {
        static Verdict allow() {
            return new Verdict(true, null, 0L);
        }

        static Verdict deny(String reason, long retryAfterMillis) {
            return new Verdict(false, reason, retryAfterMillis);
        }
    }

    /**
     * 未成年限额的判定结果。
     *
     * @param withinLimit   本次金额是否在限额内
     * @param remainingCents 本月还剩多少额度（分）。**即使超限也要给**：
     *                      只说「不行」而不说「还剩多少」就是硬拦截，
     *                      而合规要求的是友好提示
     * @param notice        给玩家看的文案；在限额内时为 null（不该打扰正常付费）
     */
    public record MinorVerdict(boolean withinLimit, long remainingCents, String notice) {
    }

    private final Rules rules;
    /** playerId → 首次付费时刻。0 表示从未付费 */
    private final Map<String, Long> firstPayAt = new HashMap<>();
    /** "playerId:giftId" → 最近 24h 的弹出时刻 */
    private final Map<String, java.util.Deque<Long>> giftShows = new LinkedHashMap<>();
    /** playerId → 上一次弹出任意礼包的时刻 */
    private final Map<String, Long> lastShowAt = new HashMap<>();

    public PopupThrottle(Rules rules) {
        if (rules == null) {
            throw new IllegalArgumentException("rules 不得为 null");
        }
        this.rules = rules;
    }

    /**
     * 判定某个礼包此刻能不能弹。
     *
     * @param viewerIsBot 观看者是否是 Bot。<b>B11 §七 红线：Bot 不得出现在任何付费弹窗场景</b>。
     *                    这个参数存在的意义是让「忘了判断」在编译期就报错 ——
     *                    如果它不是必填参数，某个新增的弹窗调用点漏掉判断时不会有任何提示
     */
    public Verdict shouldShow(String playerId, String giftId, boolean viewerIsBot, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (giftId == null || giftId.isBlank()) {
            throw new IllegalArgumentException("giftId 不得为空");
        }
        if (viewerIsBot) {
            return Verdict.deny("Bot 不得出现在任何付费弹窗场景（B11 §七 合规红线）", 0L);
        }

        long firstPay = firstPayAt.getOrDefault(playerId, 0L);
        if (firstPay > 0 && now - firstPay < rules.firstPayQuietMillis()) {
            long remain = rules.firstPayQuietMillis() - (now - firstPay);
            return Verdict.deny("首次付费后 " + (rules.firstPayQuietMillis() / 3600_000L)
                    + " 小时内不弹任何付费弹窗（验收 6）", remain);
        }

        long last = lastShowAt.getOrDefault(playerId, 0L);
        if (last > 0 && now - last < rules.globalCooldownMillis()) {
            return Verdict.deny("全局弹窗冷却中（" + (rules.globalCooldownMillis() / 60_000L) + " 分钟）",
                    rules.globalCooldownMillis() - (now - last));
        }

        String key = playerId + ":" + giftId;
        java.util.Deque<Long> shows = giftShows.computeIfAbsent(key, k -> new java.util.ArrayDeque<>());
        long windowStart = now - 24L * 3600_000L;
        while (!shows.isEmpty() && shows.peekFirst() <= windowStart) {
            shows.pollFirst();
        }
        if (shows.size() >= rules.perGiftDailyMax()) {
            Long oldest = shows.peekFirst();
            long retry = oldest == null ? 0L : Math.max(1L, oldest + 24L * 3600_000L - now);
            return Verdict.deny("同一礼包 24 小时内已弹 " + shows.size() + " 次，达上限 "
                    + rules.perGiftDailyMax(), retry);
        }
        return Verdict.allow();
    }

    /**
     * 记录一次实际弹出。<b>判定与记账分开</b>：shouldShow 是纯查询，
     * 只有真的弹出去了才调本方法 —— 否则「问了一下但没弹」会白白吃掉一次配额，
     * 而 UI 层在布局阶段经常会先问一次。
     */
    public void recordShown(String playerId, String giftId, long now) {
        if (playerId == null || giftId == null) {
            throw new IllegalArgumentException("playerId 与 giftId 都不得为 null");
        }
        giftShows.computeIfAbsent(playerId + ":" + giftId, k -> new java.util.ArrayDeque<>()).addLast(now);
        lastShowAt.put(playerId, now);
    }

    /** 记录首次付费时刻。只会记第一次 —— 「首次付费后 24 小时」指的是历史上第一次。 */
    public void recordFirstPay(String playerId, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        firstPayAt.putIfAbsent(playerId, now);
    }

    /** 某个玩家的首次付费时刻；从未付费为 0。 */
    public long firstPayAt(String playerId) {
        return firstPayAt.getOrDefault(playerId, 0L);
    }

    /**
     * 未成年付费限额判定（B15 §3 与验收 4：单次与月度两条限额，超额给友好提示而不是硬拦截）。
     *
     * <p><b>两条限额在同一个函数里判，不拆成两处</b>：拆开的下场是"改了月度忘了单次"，
     * 而少判一条的表现是玩家这一笔照扣、下个月才发现额度对不上。
     *
     * @param singleLimitCents  单笔限额（分）。由实名认证结果决定，本类不算
     * @param monthlyLimitCents 本月限额（分）
     * @param spentThisMonthCents 本月已花（分），由订单表算（见 {@code PayOrderStore#paidCentsSince}）
     * @param thisOrderCents    本次订单金额（分）
     */
    public MinorVerdict minorPayNotice(long singleLimitCents, long monthlyLimitCents,
                                       long spentThisMonthCents, long thisOrderCents) {
        if (singleLimitCents < 0 || monthlyLimitCents < 0
                || spentThisMonthCents < 0 || thisOrderCents < 0) {
            throw new IllegalArgumentException("金额不得为负：singleLimit=" + singleLimitCents
                    + " monthlyLimit=" + monthlyLimitCents
                    + " spent=" + spentThisMonthCents + " order=" + thisOrderCents);
        }
        long remaining = Math.max(0L, monthlyLimitCents - spentThisMonthCents);
        if (monthlyLimitCents == 0 || singleLimitCents == 0) {
            // 限额为 0 表示该账号不允许任何付费（通常是未满 8 岁）。
            // 仍然给文案而不是抛错：抛错会让客户端只能显示一个通用错误，
            // 而合规要求的是「明确告知为什么」
            return new MinorVerdict(false, 0L,
                    "根据未成年人保护规定，当前账号暂不支持付费。如有疑问可通过设置页的客服入口咨询");
        }
        if (thisOrderCents > singleLimitCents) {
            // 先判单笔：这条比"本月还剩多少"更具体，玩家能直接照做（换个更小的档位）
            return new MinorVerdict(false, remaining,
                    "本次需要 " + formatCents(thisOrderCents) + " 元，超过未成年账号单笔上限 "
                            + formatCents(singleLimitCents) + " 元。可以拆成更小的档位，"
                            + "或等下月额度刷新；如需帮助可通过设置页的客服入口咨询");
        }
        if (thisOrderCents <= remaining) {
            if (remaining == monthlyLimitCents) {
                // 额度充足且还没花过：不打扰正常付费
                return new MinorVerdict(true, remaining, null);
            }
            return new MinorVerdict(true, remaining,
                    "本月未成年付费额度剩余 " + formatCents(remaining) + " 元");
        }
        return new MinorVerdict(false, remaining,
                "本次需要 " + formatCents(thisOrderCents) + " 元，超出本月剩余额度 "
                        + formatCents(remaining) + " 元。你可以选择更小的档位，"
                        + "或等下月额度刷新；如需帮助可通过设置页的客服入口咨询");
    }

    /**
     * 分 → 元的展示文本。
     *
     * <p>整数运算：先取整元再取余分，余分为 0 时不显示小数。
     * 用 {@code cents / 100.0} 会在 1999 分上得到 19.990000000000002，
     * 而支付页面上出现这种数字会让玩家怀疑自己被骗了。
     */
    public static String formatCents(long cents) {
        long yuan = cents / 100L;
        long fen = cents % 100L;
        return fen == 0 ? String.valueOf(yuan) : yuan + "." + (fen < 10 ? "0" + fen : String.valueOf(fen));
    }

    /** 清理某个玩家的状态（退号、跨赛季）。 */
    public void forget(String playerId) {
        firstPayAt.remove(playerId);
        lastShowAt.remove(playerId);
        giftShows.keySet().removeIf(key -> key.startsWith(playerId + ":"));
    }

    public Rules rules() {
        return rules;
    }
}
