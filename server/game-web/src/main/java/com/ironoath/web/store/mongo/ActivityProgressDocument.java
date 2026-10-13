package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.core.activity.ActivityProgress;
import com.ironoath.web.activity.ActivityProgressStore;

/**
 * 职责：活动进度的 MongoDB 文档（一个玩家一份，条目内嵌）。
 * 依赖：{@link ActivityProgressStore.State}。
 *
 * <p>{@code _id} 就是 playerId：进度是"这个人的这一份"，没有第二个维度要拼进键里。
 * 条目内嵌成一个数组而不是一人一条（与 {@code QuestProgressDocument} 同一条理由）：
 * 最多十几条，一次点查读完，而没有任何一处需要"按条目单独改"。
 *
 * <p>{@code serverOpenMs} 也存一份：它属于部署参数（{@code SERVER_OPEN_AT}），
 * 读出来能让"这台服的开服时刻变过"这件事在数据里看得见 —— 窗口起点已经落在每个条目上，
 * 恢复时仍使用这份原锚，不能被后来改动的部署参数覆盖；instances 保存本轮完整承诺。
 */
public record ActivityProgressDocument(
        @Id String id,
        List<ActivityProgress.Entry> entries,
        long serverOpenMs,
        List<com.ironoath.web.activity.ActivityInstance> instances,
        List<com.ironoath.web.activity.ActivityClaimPlan> pendingClaims) {

    /** 集合名。 */
    public static final String COLLECTION = "activity_progress";

    static ActivityProgressDocument of(ActivityProgressStore.State state) {
        return new ActivityProgressDocument(state.playerId(), List.copyOf(state.entries()),
                state.serverOpenMs(), state.instances(), state.pendingClaims());
    }

    ActivityProgressStore.State toState() {
        return new ActivityProgressStore.State(id, entries == null ? List.of() : List.copyOf(entries),
                serverOpenMs, instances, pendingClaims);
    }
}
