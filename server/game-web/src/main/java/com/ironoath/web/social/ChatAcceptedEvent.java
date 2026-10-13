package com.ironoath.web.social;

/** 已通过真人聊天校验并落库的消息；订阅方不得阻止已经成功的发送。 */
public record ChatAcceptedEvent(String channelKey, SocialStore.ChatMessage message) { }
