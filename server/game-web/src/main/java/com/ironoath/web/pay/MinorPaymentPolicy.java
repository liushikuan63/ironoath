package com.ironoath.web.pay;

/**
 * 职责：回答"这个账号是不是未成年"。限额的<b>金额</b>不在这里，读 {@code global.MINOR_PAY_*}。
 * 依赖：无（纯接口）。
 *
 * <p><b>为什么单独一个端口</b>：判定函数（{@code PopupThrottle#minorPayNotice}）与月度账本
 * （{@code PayOrderStore#paidCentsSince}）都是确定的，唯一缺的是"这个账号要不要限"。
 * 把这一个未知收成一个端口，实名落地时<b>只需要换掉这一个实现</b>；
 * 如果在支付服务里直接写 {@code if (player.isMinor())}，那条判断会散到下单、礼包、月卡三个入口，
 * 漏一处的表现是"同一笔钱在 A 处被拦、在 B 处照扣"。
 *
 * <p><b>金额刻意不从这里的返回值给</b>：那样等于把配置表里的两个参数变成"实现说了算"，
 * 而 B15 §3 的限额是监管口径，必须只有 {@code global} 一处来源。
 */
public interface MinorPaymentPolicy {

    /**
     * @param playerId 玩家 id
     * @return {@code TRUE} 未成年（施加限额）、{@code FALSE} 已确认成年（不限额）、
     *         {@code null} <b>无法判定</b>（本仓库现状：没有实名认证）。
     *
     *         <p>三态是必要的：如果把"不知道"折叠成 {@code FALSE}，"没接实名"与"确实成年"
     *         在数据上就长得一样，将来没人能查出还有哪些路径没接上；折叠成 {@code TRUE}
     *         则会在全服没有年龄数据时把所有人的付费都锁死。"不知道"必须由调用方**看见并说出来**。
     */
    Boolean minorFlagOf(String playerId);

    /** 默认实现：年龄未知。支付链路会因此不拦截，但每次下单都记一条 WARN。 */
    MinorPaymentPolicy UNKNOWN = playerId -> null;
}
