package com.ironoath.web.pay;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ironoath.config.cfg.PayProductCfg;
import com.ironoath.core.pay.PayOrder;
import com.ironoath.core.player.PlayerRepository;
import com.ironoath.core.player.PlayerSave;
import com.ironoath.core.reward.RewardContext;
import com.ironoath.core.reward.RewardItem;
import com.ironoath.core.reward.RewardService;
import com.ironoath.core.reward.RewardType;
import com.ironoath.web.service.PayAppService;

/**
 * 职责：B19 三类商品的<b>发货</b>（替换 B15 留到现在的那个故意失败的桩）。
 * 依赖：{@link PaidProducts}（两张付费表）、{@link RewardService}（唯一的发奖通路）、
 *       {@link PlayerRepository}（读权益位判重复）。
 *
 * <p><b>三条节奏各走各的</b>（{@code pay_product.grantOccasion}）：
 * <ul>
 *   <li>{@code ON_PURCHASE}（首充）—— 买下即发：金币 + 玩家自己挑的那名 SR + 登记首充；</li>
 *   <li>{@code DAILY}（月卡）—— 买下<strong>只延一期有效期</strong>，日包靠每天领；</li>
 *   <li>{@code TIER}（成长基金）—— 买下只登记"买过"，六档按主城等级分批领。</li>
 * </ul>
 * 后两类在发货这一步故意什么都不发：把"买了月卡"直接当成"30 天日包全预发"，
 * 月卡就失去了"每天回来点一下"的留存语义（§五②a 的理由），而基金的返还节奏也会被一次性冲掉。
 *
 * <p><b>幂等键是 orderId，不是 productId</b>：同一笔订单的重复回调与补单必须只发一份；
 * 而"这个账号第二次买月卡"是合法的续期（§五②c），不能按 productId 挡。
 * 判重记在玩家存档的 {@code fulfilledOrderIds} 里，与权益变更同一条写档路径。
 *
 * <p><b>残留的失败方向</b>（写清楚而不是藏起来）：台账是在奖励发出去<em>之后</em>才记的。
 * 若进程正好死在「奖已发、账未记」中间，补单会再发一遍。
 * 反过来先记账再发货，死在中间就是「钱收了、货永远没发、而系统认为发过了」——
 * 那一种是查不出来的。<b>两害相权取可被玩家与客服看见的那一种</b>：
 * 重复发放会在账本上留下两份痕迹，静默丢失什么都不会留。
 */
public final class ProductFulfilment implements PayAppService.ProductFulfiller {

    private static final Logger LOG = LoggerFactory.getLogger(ProductFulfilment.class);

    private final PaidProducts catalog;
    private final RewardService rewardService;
    private final PlayerRepository players;

    public ProductFulfilment(PaidProducts catalog, RewardService rewardService,
                             PlayerRepository players) {
        if (catalog == null || rewardService == null || players == null) {
            throw new IllegalArgumentException("PaidProducts / RewardService / PlayerRepository 都不得为 null");
        }
        this.catalog = catalog;
        this.rewardService = rewardService;
        this.players = players;
    }

    @Override
    public Result deliver(String playerId, String orderId, PayOrder.Line line, long now) {
        if (line.cardTerms() != null) return deliverMonthlyPurchase(playerId, orderId, line, now);
        PayProductCfg product = catalog.require(line.productId());
        if (product.kind() == PayProductCfg.Kind.MONTHLY_MATERIAL_CARD || product.kind() == PayProductCfg.Kind.MONTHLY_GOLD_CARD)
            return Result.failure("新月卡订单缺少购买时条款快照，请恢复原订单后补发。");
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException(
                        "发货时玩家存档不存在：playerId=" + playerId + " orderId=" + orderId
                                + "。这单必须留在补单队列里等人处理，不能在这里判定为已发货"));
        if (save.paid().fulfilled(orderId)) {
            List<RewardItem> replay = plannedRewards(product, line);
            LOG.info("订单已发过货，幂等回放 orderId={} playerId={} 商品={} 奖励={} 项",
                    orderId, playerId, product.id(), replay.size());
            return new Result(true, replay, null);
        }
        // 一次性商品被买第二次（下单处已拦，这里是补单/并发下的第二道）：
        // 返回失败让订单留在 PAID_UNFULFILLED —— 钱收了但这份权益本账号已经用掉了，
        // 那是客服要看得见的一笔，不是一个可以静默吞掉的边角
        String alreadyOwned = catalog.alreadyOwned(playerId, save.paid(), product);
        if (alreadyOwned != null) {
            return Result.failure(alreadyOwned);
        }

        List<RewardItem> plan = plannedRewards(product, line);
        if (plan.isEmpty()) {
            return Result.failure("商品 " + product.id() + " 没有配任何可发的内容："
                    + "pay_product / product_reward 两张表缺一行，宁可判发货失败进补单，"
                    + "也不能给玩家一个「已到账」而什么都没发");
        }
        // sourceRef 用 orderId：溢出转邮件时邮件上带的就是这个订单号，
        // 玩家拿着截图来问"我那次充值的背包里没有"，客服能从邮件倒查到订单
        var result = rewardService.grant(playerId, plan,
                new RewardContext("pay", orderId, orderId, true));
        markFulfilled(playerId, orderId);
        List<RewardItem> granted = new ArrayList<>(result.granted());
        if (result.hasCompensation()) {
            LOG.error("付费发货部分未入账，已进补偿队列 orderId={} playerId={} compensationId={}"
                    + "：玩家的这一单需要人工看一眼", orderId, playerId, result.compensationId());
        }
        return new Result(true, granted, null);
    }

    /** 这一单<b>应该</b>发什么。第一次发货与幂等回放都问它，两条路径因此不会给出两套答案。 */
    private List<RewardItem> plannedRewards(PayProductCfg product, PayOrder.Line line) {
        List<RewardItem> out = new ArrayList<>();
        switch (product.grantOccasion()) {
            // 登记位排在最前：万一后面的发放中途崩掉，"这个账号已经用掉首充"这件事已经落下，
            // 补单重放时不会把金币再送一遍
            case ON_PURCHASE -> {
                // 首充/基金这一类要落一个"账号级登记位"，礼包不在此列：它没有任何永久语义，
                // 每天可以重买一次同样的内容。给它登记一条 PRIVILEGE 的后果不是多一个字段，
                // 而是 PaidPrivilegeGrants 的 switch 里没有 GIFT 这一支 ⇒ 默认分支当场抛，
                // 玩家付了钱而发货失败、进补单队列反复重试。所以这里按 kind 分流。
                if (product.kind() != PayProductCfg.Kind.GIFT) {
                    out.add(new RewardItem(RewardType.PRIVILEGE, product.id(), 1L));
                }
                out.addAll(catalog.toDomainRewards(
                        catalog.alwaysRows(product.id()), 1L));
                RewardItem hero = heroChoiceReward(product, line);
                if (hero != null) {
                    out.add(hero);
                }
            }
            // 月卡：发货只买回"有效期"，日包是另一件事（每天领）
            case DAILY -> {
                Long days = product.durationDays();
                if (days == null) {
                    throw new IllegalStateException("商品 " + product.id()
                            + " 的 grantOccasion=DAILY 但 durationDays 为空：日包没有可延的周期");
                }
                out.add(new RewardItem(RewardType.PRIVILEGE, product.id(), days));
            }
            case TIER -> out.add(new RewardItem(RewardType.PRIVILEGE, product.id(), 1L));
            default -> throw new IllegalStateException("未支持的发货节奏 " + product.grantOccasion()
                    + "（pay_product 新增取值时要在 " + getClass().getSimpleName() + " 里登记它怎么发）");
        }
        return List.copyOf(out);
    }

    /** 首充的三选一。武将是玩家下单时自己挑的，这里只照着他挑的发（B19 §五②e）。 */
    private RewardItem heroChoiceReward(PayProductCfg product, PayOrder.Line line) {
        List<String> candidates = catalog.heroChoices(product);
        if (candidates.isEmpty()) {
            return null;
        }
        String picked = line.heroChoice();
        if (picked == null || !candidates.contains(picked)) {
            throw new IllegalStateException("商品 " + product.id() + " 要求从 " + candidates
                    + " 里挑一名武将，订单里记的是 " + picked
                    + "：发货时不替玩家补一个默认值（那是把一次本该由玩家做的决定替他做了）");
        }
        return new RewardItem(RewardType.HERO, picked, 1L);
    }

    /** 把 orderId 记进玩家的发货台账。单独一次写档，见类注释里那条失败方向的取舍。 */
    private void markFulfilled(String playerId, String orderId) {
        PlayerSave save = players.findByPlayerId(playerId)
                .orElseThrow(() -> new IllegalStateException("回读存档失败：playerId=" + playerId));
        save.setPaid(save.paid().withFulfilledOrder(orderId));
        players.save(save);
    }

    /** 周期先持久接受一次，初始金币由稳定订单回执发放；丢ack补单不会再延一期。 */
    private Result deliverMonthlyPurchase(String playerId, String orderId, PayOrder.Line line, long now) {
        PlayerSave save = players.findByPlayerId(playerId).orElseThrow();
        var purchase = save.paid().monthlyPurchases().get(orderId);
        if (purchase == null) {
            long lastEnd = save.paid().monthlyPurchases().values().stream()
                    .filter(p -> p.productId().equals(line.productId())).mapToLong(p -> p.expiresAt()).max().orElse(now);
            long start = Math.max(now, lastEnd);
            long duration = Math.multiplyExact(Math.multiplyExact(line.cardTerms().durationDays(), line.count()), 86_400_000L);
            purchase = new com.ironoath.core.pay.MonthlyCardPurchase(orderId, line.productId(), line.cardTerms(),
                    start, Math.addExact(start, duration), line.count(), 0);
            save.setPaid(save.paid().withMonthlyPurchase(purchase));
            players.save(save);
        } else if (!purchase.productId().equals(line.productId()) || !purchase.terms().equals(line.cardTerms())
                || purchase.quantity() != line.count()) {
            throw new IllegalStateException("月卡稳定订单身份不可对应不同购买条款");
        }
        long initial = Math.multiplyExact(purchase.terms().initialGold(), purchase.quantity());
        List<RewardItem> granted = new ArrayList<>();
        granted.add(new RewardItem(RewardType.PRIVILEGE, purchase.productId(), purchase.totalDays()));
        if (initial > 0) {
            var result = rewardService.grant(playerId, List.of(new RewardItem(RewardType.RESOURCE, "GOLD", initial)),
                    new RewardContext("paid_gold", orderId, "monthly:purchase:" + orderId, true,
                            java.util.Set.of("monthly-product:" + purchase.productId())));
            // 订单历史记录累计已交付（含转邮件），丢ack补单的本次delta=0仍不能漏掉原300。
            granted.add(new RewardItem(RewardType.RESOURCE, "GOLD", initial));
        }
        markFulfilled(playerId, orderId);
        return new Result(true, List.copyOf(granted), null);
    }

}
