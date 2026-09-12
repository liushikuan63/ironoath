package com.ironoath.web.bot;

import com.ironoath.core.bot.BotChatBook;

/**
 * 职责：给「托管账号该因这件事说一句话」用的领域事件（B11 §四「事件触发模板句库」）。
 * 依赖：game-core 的 {@link BotChatBook.Scene}（只借它的场景枚举）。
 *
 * <p><b>为什么用事件而不是让事件方直接调 {@code BotWorldAdapter}</b>：事件发生在战斗结算、
 * 城建升级、集结发起这些地方，而适配器依赖着那一整片服务（`MarchAppService`、`WarmAppService`…）。
 * 直接注入会立刻构成构造环（本仓库已经踩过两次 `BeanCurrentlyInCreation`：
 * #93 的兜底驱动、#95 的校准回填，两次都是靠改依赖方向解决的）。事件把方向掰直：
 * <b>发布方只知道「发生了什么」，不认识任何 Bot 类型</b>；订阅方在 web 的 bot 包内，
 * 也就是依赖图的下游。与 {@code PowerChangedEvent} → {@code MatchPool} 同一个范本。
 *
 * <p><b>同步发布、订阅方自己吞异常</b>：与电力事件那条不同，这里的订阅方会走一次
 * 真实写库（发言进聊天频道），所以它必须自己兜住 —— 一句寒暄发不出去，
 * 不该把别人那一场战斗结算变成 500。
 *
 * <p><b>它不是「Bot 事件」，是「谁过来说句话」的请求</b>：真人身上也会发生同样的事
 * （被打、升级、开集结），所以字段里没有 isBot 这类标记 —— 谁是 Bot 由订阅方向注册表问
 * （{@code check-no-bot-privilege.sh} 只允许注册表回答这个问题）。
 *
 * @param playerId 发生这件事的人（可能说一句话的是他，也可能是别人，由 scene 决定看谁）
 * @param speakerId 该说话的人。多数事件里等于 playerId；受击事件里是受害者本人
 * @param scene    该说哪一类话。由发布方按「发生了什么」选，不由订阅方猜
 * @param atMillis 事件时刻（服务端时间戳）
 */
public record BotChatEvent(String playerId, String speakerId, BotChatBook.Scene scene, long atMillis) {

    public BotChatEvent {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("playerId 不得为空：没有归属的事件无法归因");
        }
        if (speakerId == null || speakerId.isBlank()) {
            throw new IllegalArgumentException("speakerId 不得为空：不知道该让谁说话");
        }
        if (scene == null) {
            throw new IllegalArgumentException("scene 不得为 null：不知道该说哪一类话");
        }
        if (atMillis <= 0L) {
            throw new IllegalArgumentException("atMillis 必须为正的服务端时间戳，实际=" + atMillis);
        }
        if (!scene.eventDriven()) {
            // 闲聊型场景（CHAT_IDLE / TRADE / ALLIANCE_JOIN）由 tick 的自发节奏说，
            // 不能让一个业务事件把它们钓出来 —— 那会说出一句与当下无关的话
            throw new IllegalArgumentException("场景 " + scene + " 不是事件触发型，"
                    + "事件只能携带 BotChatBook.Scene 里 eventDriven() 为 true 的场景");
        }
    }

    /** 一键构造：说话人就是当事人。 */
    public static BotChatEvent of(String playerId, BotChatBook.Scene scene, long atMillis) {
        return new BotChatEvent(playerId, playerId, scene, atMillis);
    }
}
