package com.ironoath.web.reward;

import java.util.concurrent.TimeUnit;

import com.ironoath.config.ConfigException;
import com.ironoath.config.ConfigRegistry;
import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;

/**
 * 职责：把 {@code RewardType.PRIVILEGE} 这一类「不是东西、是状态位」的奖励落到玩家存档的付费权益上（B19）。
 * 依赖：{@link PlayerRepository}（读写 PlayerSave）、{@link ConfigRegistry}（读 pay_product 判类别）。
 *
 * <p><b>为什么特权要走发放器而不是让支付域自己写存档</b>：礼包、活动、邮件附件将来都会给特权
 * （B19 §一.3 的礼包就带免广告券）。每条通路各写一份「怎么延期、怎么登记」，
 * 就会有一处忘记叠期、另一处把已领的基金再登记一次 —— 而特权是付过钱的东西，分叉的代价是客诉。
 * 现在全库只有这一处会改 {@code PlayerPaid} 里的权益位（另一处是支付域自己的领取账本）。
 *
 * <p><b>返回 {@code count} 而不是「实际到账量」</b>：时间不会满仓，也没有「装不下所以转邮件」这回事。
 * 若照 {@code Wallet} 的语义返回小于 count 的值，发放器会把差额当成溢出（{@code RewardGrantor} 的
 * {@code rest > 0 → overflow}）并给玩家寄一封「特权 ×30」的邮件 —— 那是把 30 天当道具发第二遍。
 *
 * <p><b>调用方必须在玩家锁内</b>：这里做的是「读档 → 改 → 写档」，与 {@link PlayerWallet} 同一条纪律。
 *
 * <p><b>不认识的商品 id 一律抛</b>：静默返回 0 会让玩家看到「已获得月卡」而存档里什么都没有，
 * 那正是 B06 反复防的「假发放」。
 */
public final class PaidPrivilegeGrants {

    private static final long MILLIS_PER_DAY = TimeUnit.DAYS.toMillis(1);

    private final PlayerRepository players;
    private final ConfigRegistry configs;

    public PaidPrivilegeGrants(PlayerRepository players, ConfigRegistry configs) {
        if (players == null || configs == null) {
            throw new IllegalArgumentException("PlayerRepository / ConfigRegistry 不得为 null");
        }
        this.players = players;
        this.configs = configs;
    }

    /**
     * 发一份特权。
     *
     * @param privilegeId {@code pay_product} 的行 id（月卡 / 基金 / 首充）—— 特权"是什么"由商品表定义
     * @param count 时长类特权（月卡）= 天数；登记类特权（基金、首充）= 必须为 1
     * @return 恒等于传入的 count（见类注释：时间没有溢出这一说）
     */
    public long grant(String playerId, String privilegeId, long count, long now) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空");
        }
        if (count <= 0L) {
            throw new IllegalArgumentException("特权发放量必须为正：id=" + privilegeId + ", count=" + count);
        }
        PayProductCfg product = productOf(privilegeId);
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("玩家存档不存在，特权无处安放: " + playerId));
        var paid = save.paid();
        switch (product.kind()) {
            case MONTHLY_CARD -> {
                save.setPaid(paid.withCardExtended(now, count * MILLIS_PER_DAY));
            }
            // 基金与首充是"一次性登记"，天数对它们没有意义：
            // 传 30 进来只可能是拼奖励的人把月卡的语义套用过来了，那种错必须当场炸
            case GROWTH_FUND -> {
                requireSingle(product, count);
                save.setPaid(paid.withFundPurchased(now));
            }
            case FIRST_CHARGE -> {
                requireSingle(product, count);
                save.setPaid(paid.withFirstCharged(now));
            }
            default -> throw new IllegalArgumentException("未支持的付费商品类别 " + product.kind()
                    + "（pay_product 新增 kind 时要在 " + getClass().getSimpleName() + " 里登记它怎么落库）");
        }
        players.save(save);
        return count;
    }

    /** 特权 id 必须是 pay_product 的行 id：换一个字符串就是往第二个家写。 */
    private PayProductCfg productOf(String privilegeId) {
        if (privilegeId == null || privilegeId.isBlank()) {
            throw new IllegalArgumentException("PRIVILEGE 的 id 必须是 pay_product 的行 id，实际为空");
        }
        try {
            return configs.get(PayProductCfg.class, privilegeId);
        } catch (ConfigException e) {
            throw new IllegalArgumentException("PRIVILEGE 的 id 必须是 pay_product 的行 id，实际="
                    + privilegeId + "（表里没有这一行，特权无处落地）");
        }
    }

    private static void requireSingle(PayProductCfg product, long count) {
        if (count != 1L) {
            throw new IllegalArgumentException("登记类特权一次只能登记一份：" + product.id()
                    + " 收到 count=" + count + "。天数是月卡的语义，别套到" + product.kind() + "上");
        }
    }
}
