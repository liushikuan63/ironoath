package com.ironoath.web.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.ironoath.core.pay.PayOrder;
import com.ironoath.web.dto.generated.PayCallbackReq;
import com.ironoath.web.service.PayAppService;

/**
 * 职责：装配支付域的三个部件 —— 订单登记簿、回调验签、发货。
 * 依赖：Spring Boot、game-core 的 {@link PayOrder}。
 *
 * <p><b>这里仍有一个「本地开发用」的失败实现，另一个已经被换掉</b>：
 * B15 的红线是「在做完合规前不许上线任何付费」，而最容易出的事故是
 * 某个占位实现在无人注意的情况下被带上了生产。所以：
 * <ul>
 *   <li>验签实现声明 {@code productionReady() = false}，{@code PayAppService} 构造时会打 WARN，
 *       上线检查清单 §三 1 也把它列为硬阻塞</li>
 *   <li><b>发货曾经是"什么都不发"的桩，B19 已换成真实现</b>（月卡 / 基金 / 首充三类各自成套，
 *       这正是当初那个桩写下的待办）。它保留的是同一个失败方向：
 *       任何一步发不出去就返回失败，订单留在 PAID_UNFULFILLED 进补单队列，
 *       客服看得见（{@code retryQueued=true}）。反过来（假装发货成功）会让一笔钱在账面上凭空消失，
 *       而那种损失是查不出来的</li>
 * </ul>
 *
 * <p><b>订单登记簿默认是内存的</b>，重启即丢 —— 对支付来说这是最不能接受的一类丢失
 * （玩家付了钱而订单没了）。{@code ironoath.storage=mongo} 时换成
 * {@link com.ironoath.web.store.mongo.MongoPayOrderStore}：两边跑同一份
 * {@code PayOrderStoreEquivalenceTest}，所以「已付款未发货」这笔负债在两种存储下都落得住。
 * 内存 bean 因此带 {@code @ConditionalOnProperty} —— 不是为了让配置好看，
 * 而是同类型两个 bean 同时存在时 Spring 会在启动期直接报歧义，
 * 换句话说「忘了切 mongo」这件事现在会响，不会静默用内存版上线。
 */
@Configuration
public class PayBeansConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PayBeansConfig.class);

    @Bean
    @ConditionalOnProperty(name = "ironoath.storage",
            havingValue = GameProperties.STORAGE_MEMORY, matchIfMissing = true)
    public PayOrder.Registry payOrderRegistry() {
        LOG.warn("使用内存订单登记簿：进程重启后全部订单丢失，已付款未发货的订单将无从追查。"
                + "支付域的持久化优先级高于其它所有内存存储（玩家付了钱而订单没了是不可接受的），"
                + "生产请设 ironoath.storage=mongo（该实现已存在，不用它属于配置遗漏而不是能力缺失）");
        return new PayOrder.Registry();
    }

    /**
     * 付费弹窗频控 + 未成年限额判定。三条口径全部来自 {@code global}，
     * 单位换算（小时/分钟 → 毫秒）留在这装配层，core 只收已解析好的值（B01 的决定）。
     */
    @Bean
    public com.ironoath.core.pay.PopupThrottle payPopupThrottle(
            com.ironoath.config.ConfigRegistry configs) {
        return new com.ironoath.core.pay.PopupThrottle(new com.ironoath.core.pay.PopupThrottle.Rules(
                configs.longParam("PAY_FIRST_PURCHASE_QUIET_HOURS") * 3_600_000L,
                (int) configs.longParam("PAY_POPUP_PER_GIFT_DAILY_MAX"),
                configs.longParam("PAY_POPUP_GLOBAL_COOLDOWN_MINUTES") * 60_000L));
    }

    /**
     * 年龄来源。<b>默认「不知道」，于是不拦任何人的付费</b> —— 用
     * {@code @ConditionalOnMissingBean} 而不是直接 {@code @Bean}，是为了让
     * 实名落地（或测试）能在同一个上下文里换一个实现，而不是靠"后注册的覆盖先注册的"
     * 那种要看注册顺序的把戏。真实的实名实现注册之后，{@code PayAppService}
     * 构造期那条"限额未生效"的 WARN 就会消失 —— 那条 WARN 消失与否就是这项合规的验收点。
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public com.ironoath.web.pay.MinorPaymentPolicy minorPaymentPolicy() {
        return com.ironoath.web.pay.MinorPaymentPolicy.UNKNOWN;
    }

    /**
     * 回调验签。<b>本地开发实现：任何签名都通过</b>。
     *
     * <p>做成端口而不是在 service 里写 {@code if (dev)} 分支的理由：
     * 分支版本在正式环境里只要有一个配置项没设对就会静默退化成「不验签」，
     * 而不验签的回调端点等于任何人 POST 一下就能给自己发货。
     * 端口版本要求正式环境必须提供实现，缺了就起不来。
     */
    @Bean
    public PayAppService.SignatureVerifier paySignatureVerifier() {
        LOG.warn("支付验签使用本地开发实现：任何回调都会被判定为可信。"
                + "上线前必须替换为米大师验签（商户密钥来自环境变量，不进版本库）");
        return new PayAppService.SignatureVerifier() {
            @Override
            public boolean verify(PayCallbackReq callback, PayOrder order) {
                return callback != null && callback.sign() != null && !callback.sign().isBlank();
            }

            @Override
            public boolean productionReady() {
                return false;
            }
        };
    }

    /**
     * 两张付费表（pay_product / product_reward）唯一的读处，装配成一个 bean 而不是各处 new：
     * 价格、发货内容、当前权益三件事必须由同一份读法算出来，否则"表改了一列"只会有一处跟着改。
     */
    @Bean
    public com.ironoath.web.pay.PaidProducts paidProducts(com.ironoath.config.ConfigRegistry configs,
            com.ironoath.web.battlepass.BattlePassService battlePass) {
        return new com.ironoath.web.pay.PaidProducts(configs, battlePass);
    }

    /**
     * 发货（B19 §一.1）。<b>这一位从"故意失败的桩"换成了真实现</b>，类注释里那条
     * 「所有已付款订单都会进补单队列」的 WARN 因此删掉了。
     *
     * <p>三类商品的节奏各走各的：首充买下即发（金币 + 玩家自己挑的武将），月卡买下只延一期有效期
     * （日包是每天领的），成长基金买下只登记"买过"（六档按主城等级分批领）。
     * 幂等键是 orderId，记在玩家存档的发货台账里 —— 订单状态机只挡状态迁移，
     * 挡不住补单把同一份奖励发两遍（B19 开工提示词第 1 条）。
     *
     * <p><b>失败方向没有变</b>：任何一步发不出去（商品没配内容、一次性商品被第二次买、
     * 玩家锁拿不到）都返回失败，订单留在 PAID_UNFULFILLED 进补单队列。
     * 「钱收了货没发」是可追查的，假装发货成功则会让一笔钱在账面上凭空消失。
     */
    @Bean
    public PayAppService.ProductFulfiller productFulfiller(
            com.ironoath.web.pay.PaidProducts catalog,
            com.ironoath.core.reward.RewardService rewardService,
            com.ironoath.core.player.PlayerRepository players) {
        return new com.ironoath.web.pay.ProductFulfilment(catalog, rewardService, players);
    }
}
