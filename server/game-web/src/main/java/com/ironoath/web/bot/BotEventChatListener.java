package com.ironoath.web.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 职责：把 {@link BotChatEvent} 变成一句真的发进联盟频道的话（B11 §四 事件触发句库的订阅侧）。
 * 依赖：只有 {@link BotWorldAdapter}（它持有句库与真人那条 {@code chatSend}）。
 *
 * <p><b>为什么单独一个类而不是让适配器自己 {@code @EventListener}</b>：适配器是
 * {@code BotScheduler.World} 的实现，它的职责是「把一次决策变成几次 service 调用」；
 * 而这里是「把世界上发生的事反射成一句话」——两个方向相反。
 * 分开之后还有一个可核对的好处：<b>「事件句库接没接」这件事变成一个文件与一条用例</b>，
 * 而不是散在适配器的第 900 行里。
 *
 * <p><b>空订阅的代价</b>：没有这个类时，那 5 个场景的 11 行语料永远不会被抽到 ——
 * 表做了、句子写了、机制没接，正是本仓库反复在防的形状（卡口第 14 项的同族）。
 *
 * <p><b>订阅方必须吞掉一切异常</b>：发布方在别人的结算路径上（战斗结算、城建升级、
 * 集结发起），一句寒暄出不去不该让那场结算失败。这里连 {@code Error} 之外的
 * {@code RuntimeException} 全接住，并只留一条 INFO/WARN。
 */
@Component
public class BotEventChatListener {

    private static final Logger LOG = LoggerFactory.getLogger(BotEventChatListener.class);

    private final BotWorldAdapter adapter;

    public BotEventChatListener(BotWorldAdapter adapter) {
        this.adapter = adapter;
    }

    /** 收到事件就让该说话的人说一句。真人与已回收的 Bot 由适配器内部安静过滤。 */
    @EventListener
    public void onBotChatEvent(BotChatEvent event) {
        if (event == null) {
            return;
        }
        try {
            boolean spoken = adapter.speakOnEvent(event.speakerId(), event.scene(), event.atMillis());
            if (!spoken) {
                // 三种正常情况：不是 Bot、不在联盟、等级没到门槛。都只留一条 DEBUG 级以下的痕迹 ——
                // 事件频率与 tick 同量级，INFO 会把日志淹掉
                LOG.debug("事件场景 {} 没有产生发言（speaker={}）", event.scene(), event.speakerId());
            }
        } catch (RuntimeException e) {
            // 绝不能往外抛：发布方正在结算（一场 PVP、一次升级），一句寒暄不该让它 500。
            // 这里用 WARN 而不是 INFO —— 走到这一层说明适配器里已经兜过的异常又漏出来了，
            // 那是要修的东西，而不是"玩法常态"
            LOG.warn("事件触发发言失败（speaker={} scene={}）：{}",
                    event.speakerId(), event.scene(), e.toString());
        }
    }
}
