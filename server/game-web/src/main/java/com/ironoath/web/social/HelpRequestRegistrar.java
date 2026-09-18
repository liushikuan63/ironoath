package com.ironoath.web.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ironoath.core.player.PlayerRepository;
import com.ironoath.web.dto.generated.HelpTargetKind;
import com.ironoath.web.social.SocialStore;

/**
 * 职责：登记一条「求助请求」并通知同组织的人（B10 验收 6 的生产者侧接线）。
 * 依赖：社交存储、玩家仓储。两者都是叶子。
 *
 * <p><b>为什么单独一个组件、而不是让发起方直接调 {@code SocialAppService}</b>：
 * 求助请求的发起方是城建/军队域，而社交服务依赖着攻击闸门链
 * （{@code SocialAppService → AttackGuardService → PowerRefreshService → CityAppService}）——
 * 一旦 {@code CityAppService} 反过来注入社交服务，Spring 当场起不来（本轮真踩过：
 * {@code BeanCurrentlyInCreationException}）。把"登记 + 广播"抽成只依赖存储的叶子，
 * 依赖方向就永远只有一条：**发起方 → 登记器 → 存储**。
 *
 * <p>事件有效期放在 {@link SocialStore#EVENT_TTL_MILLIS}：三小时前的求援已经支援不上了，
 * 而这个"多久算过期"的口径必须只有一处（原先写在社交服务里，登记器再抄一份就会漂）。
 */
@Component
public class HelpRequestRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(HelpRequestRegistrar.class);

    private final SocialStore store;
    private final PlayerRepository players;
    /**
     * 事件触发的聊天（B11 §四）：升级/训练/治疗真的卡住了，本人喊一句「谁能帮我加个速」。
     *
     * <p>登记器是这条链上唯一同时知道「谁、因为什么、卡到什么时候」的地方，所以由它发布；
     * 它只认识 {@code ApplicationEventPublisher}（Spring 的叶子），不认识 Bot 的任何类型 ——
     * 依赖方向与它当年被抽出来时的那条理由一致：发起方 → 登记器 → 存储。
     */
    private final org.springframework.context.ApplicationEventPublisher events;

    public HelpRequestRegistrar(SocialStore store, PlayerRepository players,
                                org.springframework.context.ApplicationEventPublisher events) {
        this.store = store;
        this.players = players;
        this.events = events;
    }

    /**
     * 登记并广播。幂等靠 requestId：同一栋楼的同一轮升级用同一个 id 重复登记时覆盖旧记录。
     *
     * @param targetKey 加速要落到哪个目标上（建筑 instanceId 等），与给人看的 targetDesc 分工不同
     */
    public void register(String requestId, String playerId, HelpTargetKind kind, String targetKey,
                         String targetDesc, long finishAt, long now) {
        // 产品裁决（2026-09-18）：**求助必须有队或盟**，没有组织就不登记、引导玩家先加入。
        // 为什么拦在这里而不是让三个调用方各自判：求助登记是「升级建筑 / 训练 / 治疗」的**锁内副作用**，
        // 在调用方抛错误码的后果是「没小队的玩家连建筑都升不了」—— 裁决否掉的是「求助」这件事本身，
        // 不是那三个动作。今天散人照样登记成功、只是 peerPlayerIds 为空没人被通知：
        // 那是一次**没有任何人的收到方**的登记，除了在列表里躺着 60 分钟之外没有意义。
        // 引导加入队的文案由客户端按 /social/summary 里已经下发的 squad/alliance 是否为 null 自己决定 ——
        // 不在这里加字段：字段只有服务端会写、客户端还没界面读，那就是又一个「有名字零调用点」。
        if (store.squadOf(playerId).isEmpty() && store.allianceOf(playerId).isEmpty()) {
            LOG.info("求助未登记：该玩家没有小队也没有同盟，没有人能看到这条求助（引导玩家先加入组织） playerId={} 目标={}",
                    playerId, targetDesc);
            return;
        }
        store.putHelpRequest(new SocialStore.HelpRequest(requestId, playerId,
                nickname(playerId), kind.name(), targetKey, targetDesc, finishAt, 0));
        String title = nickname(playerId) + " 请求帮助：" + targetDesc;
        for (String peer : store.peerPlayerIds(playerId)) {
            store.pushEvent(peer, new SocialStore.SocialEvent(
                    "evt_HELP_REQUESTED_" + requestId + "_" + now, "HELP_REQUESTED", title, null,
                    null, null, requestId, now, now + SocialStore.EVENT_TTL_MILLIS));
        }
        LOG.info("登记求助请求 requestId={} 发起人={} 目标={} 完成于={} 通知同组织={}人",
                requestId, playerId, targetDesc, finishAt, store.peerPlayerIds(playerId).size());
        // 事件触发的聊天（B11 §四 HELP_REQUEST）：真的有人卡住了，本人喊一句。
        // 发在登记之后、且在同一个 try 之外没有额外兜底 —— 监听器自己吞异常（见 BotEventChatListener），
        // 所以这里不需要判「是不是 Bot」（那是订阅方的事）
        events.publishEvent(new com.ironoath.web.bot.BotChatEvent(playerId, playerId,
                com.ironoath.core.bot.BotChatBook.Scene.HELP_REQUEST, now));
    }

    private String nickname(String playerId) {
        return players.findByPlayerId(playerId).map(save -> save.nickName()).orElse(playerId);
    }

    /**
     * 撤回某个目标上的求助请求（目标被取消时由发起方调用）。
     *
     * <p><b>为什么必须撤回、而不是等它自然过期</b>：列表按 {@code finishAt} 过滤，
     * 取消后的请求仍然"在跑"（完成时刻还在未来），于是它会继续留在别人的可帮列表里。
     * 别人帮了它，额度真的扣掉、事件真的发出去，而目标已经不存在 —— 那正是
     * "只记账不落地"的假承诺，只不过这次吃亏的是帮忙的人（白耗一次每日额度）。
     *
     * <p>按「谁的哪个目标」定位而不是按 requestId：请求 id 的拼法属于登记方，
     * 取消方只知道自己要取消的是哪个目标（建筑 instanceId / 兵种 unitId）。
     * 同一目标同时只可能有一条请求（一栋楼不能同时升两级），所以这里最多删掉一条。
     */
    public void withdraw(String playerId, String targetKey) {
        if (targetKey == null || targetKey.isBlank()) {
            return;
        }
        int removed = 0;
        for (SocialStore.HelpRequest request : store.helpRequests()) {
            if (playerId.equals(request.fromPlayerId()) && targetKey.equals(request.targetKey())) {
                store.removeHelpRequest(request.requestId());
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("撤回求助请求 playerId={} target={} 条数={}", playerId, targetKey, removed);
        }
    }
}
