package com.ironoath.web.store.mongo;

import org.springframework.data.annotation.Id;

/** 排期的单字段读模型，避免为 matchPower 解码资源、账本与其他玩家状态。 */
public record PlayerMatchPowerDocument(@Id String playerId, Power power) {
    public record Power(long matchPower) {}
}
