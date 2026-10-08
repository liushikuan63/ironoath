package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.web.levelreward.LevelRewardClaimStore;

/**
 * 职责：等级奖励领取账本的 MongoDB 文档（一个玩家一份）。
 * 依赖：{@link LevelRewardClaimStore.State}。
 *
 * <p>{@code _id} 就是 playerId：账本是「这个人的这一份」，没有第二个维度需要拼进键里。
 *
 * <p><b>已领等级内嵌成一个数组而不是一人一条</b>：全表 40 行（现读 level_reward 行数），
 * 一次读就是一次点查；拆成行会让「读一个人的全部领取态」变成范围查询，
 * 而没有任何一处需要按单个等级单独改（领一次也是整位覆盖）。
 */
public record LevelRewardClaimDocument(
        @Id String id,
        List<Long> claimedLevels) {

    /** 集合名。 */
    public static final String COLLECTION = "level_reward_claim";

    static LevelRewardClaimDocument of(LevelRewardClaimStore.State state) {
        return new LevelRewardClaimDocument(state.playerId(), List.copyOf(state.claimedLevels()));
    }

    LevelRewardClaimStore.State toState() {
        return new LevelRewardClaimStore.State(id, claimedLevels == null ? List.of() : claimedLevels);
    }
}
