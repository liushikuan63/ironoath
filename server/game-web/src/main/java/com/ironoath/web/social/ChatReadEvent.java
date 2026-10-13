package com.ironoath.web.social;

/** 合法频道读取是惰性时间推进的一次驱动，不创建常驻聊天定时器。 */
public record ChatReadEvent(long atMillis) { }
