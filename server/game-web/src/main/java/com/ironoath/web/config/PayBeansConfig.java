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
 * <p><b>这里的两个默认实现都是「本地开发用」，而且都刻意做成显眼的失败</b>：
 * B15 的红线是「在做完合规前不许上线任何付费」，而最容易出的事故是
 * 某个占位实现在无人注意的情况下被带上了生产。所以：
 * <ul>
 *   <li>验签实现声明 {@code productionReady() = false}，{@code PayAppService} 构造时会打 WARN，
 *       上线检查清单 §三 1 也把它列为硬阻塞</li>
 *   <li>发货实现<b>什么都不发</b>并把订单推进补单队列。这是刻意的失败方向：
 *       钱收到了、货没发出去，订单留在队列里、状态对客户端可见（{@code retryQueued=true}），
 *       客服能看见。反过来（假装发货成功）会让一笔钱在账面上凭空消失，
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
     * 发货。<b>当前什么都不发</b>，理由见类注释与 {@code PayAppService} 的类注释：
     * B15 §一 的三类主力商品（特权卡每日领取、成长基金分批返还、首充双倍）各自是一套系统，
     * 不是一次奖励发放，所以「发货」这一步在那些系统交付之前没有诚实的实现可写。
     */
    @Bean
    public PayAppService.ProductFulfiller productFulfiller() {
        LOG.warn("支付发货未实现：所有已付款订单都会进补单队列（retryQueued=true）。"
                + "这是刻意的失败方向 —— 钱收到了货没发出去是可追查的，假装发货成功则会让钱凭空消失。"
                + "需要为三类商品各写一套系统：特权卡（每日领取 + 免广告 + 建造队列 +1）、"
                + "成长基金（按主城等级分批返还）、首充（双倍 + 首充武将）");
        return (playerId, line) -> PayAppService.ProductFulfiller.Result.failure(
                "商品 " + line.productId() + " 的发货逻辑尚未实现（B15 §一 的三类商品各自是一套系统）");
    }
}
