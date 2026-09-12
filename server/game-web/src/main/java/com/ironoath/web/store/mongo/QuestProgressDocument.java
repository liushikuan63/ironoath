package com.ironoath.web.store.mongo;

import java.util.List;

import org.springframework.data.annotation.Id;

import com.ironoath.core.quest.QuestProgress;
import com.ironoath.web.quest.QuestProgressStore;

/**
 * 职责：任务进度的 MongoDB 文档（一个玩家一份，条目内嵌）。
 * 依赖：{@link QuestProgressStore.State}。
 *
 * <p>{@code _id} 就是 playerId：进度是"这个人的这一份"，没有第二个维度需要拼进键里。
 *
 * <p><b>条目内嵌成一个数组而不是一人一条</b>：进度条目最多几十条（quest 表的规模），
 * 一次读就是一次点查；拆成行的话「读一个玩家的全部进度」要一次范围查询，
 * 而没有任何一处需要"按条目单独改"（改一条也总是整体由事件面板 recompute 出来）。
 */
public record QuestProgressDocument(
        @Id String id,
        List<QuestProgress.Entry> entries,
        String dayKey,
        String weekKey) {

    /** 集合名。 */
    public static final String COLLECTION = "quest_progress";

    static QuestProgressDocument of(QuestProgressStore.State state) {
        return new QuestProgressDocument(state.playerId(), List.copyOf(state.entries()),
                state.dayKey(), state.weekKey());
    }

    QuestProgressStore.State toState() {
        return new QuestProgressStore.State(id, entries == null ? List.of() : List.copyOf(entries),
                dayKey, weekKey);
    }
}
