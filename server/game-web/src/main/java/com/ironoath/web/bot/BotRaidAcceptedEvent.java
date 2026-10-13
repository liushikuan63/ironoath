package com.ironoath.web.bot;

/** 真实派兵已经成功，聊天只表达该行军，不再发动一场攻击。 */
public record BotRaidAcceptedEvent(String playerId, String targetId, String marchId, long atMillis) { }
