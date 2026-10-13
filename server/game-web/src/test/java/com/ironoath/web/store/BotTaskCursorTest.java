package com.ironoath.web.store;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.ironoath.core.bot.BotDecisionTree;
import com.ironoath.core.bot.BotScheduler;
import com.ironoath.web.store.memory.InMemoryBotTaskQueue;

class BotTaskCursorTest {
    @Test void oneBotHasOneCursorAndReplacingDecisionCannotLeaveAnOldAction() {
        var queue=new InMemoryBotTaskQueue();
        var old=BotScheduler.BotTask.decisionAt("owned-bot",100_000);
        var delayed=BotScheduler.BotTask.actionAt("owned-bot",103_000,
                new BotDecisionTree.Decision(BotDecisionTree.Action.REACT_ATTACK,false,3_000,"真实受击"));
        queue.schedule(old);
        queue.schedule(delayed);
        assertThat(queue.size()).as("B11的tickCursor每Bot只有一份；旧决定不能与新延迟动作同时存在").isEqualTo(1);
        assertThat(queue.dueBefore(102_999,10)).isEmpty();
        assertThat(queue.dueBefore(103_000,10)).containsExactly(delayed);
        assertThat(queue.dueBefore(103_000,10)).isEmpty();
    }
}
